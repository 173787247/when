package com.when.ingress.application;

import com.when.api.application.CancelResult;
import com.when.api.application.CancellationRejectedException;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageNotFoundException;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.core.ClusterView;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.NodeEndpoint;
import com.when.core.Router;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import com.when.ingress.id.MessageIdGenerator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Application-layer implementation of Submit, Query and Cancel. */
public final class DefaultDelayMessageHandler implements DelayMessageHandler {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final Duration MAX_DELAY = Duration.ofDays(30);

    private final String localNodeId;
    private final MessageIdGenerator idGenerator;
    private final Router router;
    private final ClusterView clusterView;
    private final TimeWheelRegistry timeWheels;
    private final StoragePlugin storage;
    private final DelayMessageForwarder forwarder;
    private final Clock clock;
    private final Supplier<String> traceIdGenerator;

    public DefaultDelayMessageHandler(
            String localNodeId,
            MessageIdGenerator idGenerator,
            Router router,
            ClusterView clusterView,
            TimeWheelRegistry timeWheels,
            StoragePlugin storage,
            DelayMessageForwarder forwarder) {
        this(
                localNodeId,
                idGenerator,
                router,
                clusterView,
                timeWheels,
                storage,
                forwarder,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    public DefaultDelayMessageHandler(
            String localNodeId,
            MessageIdGenerator idGenerator,
            Router router,
            ClusterView clusterView,
            TimeWheelRegistry timeWheels,
            StoragePlugin storage,
            DelayMessageForwarder forwarder,
            Clock clock,
            Supplier<String> traceIdGenerator) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.router = Objects.requireNonNull(router, "router");
        this.clusterView = Objects.requireNonNull(clusterView, "clusterView");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.traceIdGenerator = Objects.requireNonNull(traceIdGenerator, "traceIdGenerator");
    }

    @Override
    public SubmitResult submit(SubmitCommand command) {
        validate(command);
        ForwardingContext.RoutedIdentity forwarded = ForwardingContext.current();
        String messageId = forwarded == null ? requireText(idGenerator.nextId(), "messageId") : forwarded.messageId();
        String traceId = forwarded == null ? requireText(traceIdGenerator.get(), "traceId") : forwarded.traceId();
        String canonicalTimeWheelId = requireText(router.routeToTimeWheel(messageId), "timeWheelId");
        String timeWheelId = forwarded == null ? canonicalTimeWheelId : forwarded.timeWheelId();
        if (!timeWheelId.equals(canonicalTimeWheelId)) {
            throw new IngressValidationException("forwarded time wheel does not match message route");
        }

        NodeEndpoint master = masterOf(timeWheelId);
        if (!localNodeId.equals(master.nodeId())) {
            return forwarder.submit(master, new RoutedSubmit(messageId, traceId, timeWheelId, command));
        }

        // Resolve the local index before committing the durable fact, then persist before indexing.
        TimeWheel wheel = timeWheels.require(timeWheelId);
        long now = clock.millis();
        Message message = new Message(
                messageId,
                now,
                command.deliverAt(),
                timeWheelId,
                command.sinkType(),
                command.sinkConfig(),
                command.payload(),
                blankToNull(command.businessTag()),
                MessageStatus.PENDING,
                0,
                command.deliverAt(),
                0,
                null,
                traceId);
        storage.create(message);
        wheel.add(message);
        return new SubmitResult(messageId, MessageStatus.PENDING, command.deliverAt());
    }

    @Override
    public MessageView query(String messageId) {
        Message message = storage.get(requireText(messageId, "messageId"))
                .orElseThrow(() -> new MessageNotFoundException(messageId));
        return view(message);
    }

    @Override
    public CancelResult cancel(String messageId) {
        String requiredId = requireText(messageId, "messageId");
        Message message = storage.get(requiredId)
                .orElseThrow(() -> new MessageNotFoundException(requiredId));
        NodeEndpoint master = masterOf(message.timeWheelId());
        if (!localNodeId.equals(master.nodeId())) {
            return forwarder.cancel(master, requiredId);
        }

        TimeWheel wheel = timeWheels.require(message.timeWheelId());
        if (!storage.transition(
                requiredId,
                MessageStatus.PENDING,
                MessageStatus.CANCELLED,
                new StatePatch(null, null, null, null))) {
            if (storage.get(requiredId).isEmpty()) {
                throw new MessageNotFoundException(requiredId);
            }
            throw new CancellationRejectedException();
        }
        wheel.remove(requiredId);
        return new CancelResult(requiredId, MessageStatus.CANCELLED);
    }

    private NodeEndpoint masterOf(String timeWheelId) {
        return clusterView.masterOf(timeWheelId)
                .orElseThrow(() -> new TimeWheelMasterUnavailableException(timeWheelId));
    }

    private void validate(SubmitCommand command) {
        if (command == null) {
            throw new IngressValidationException("submit command is required");
        }
        long now = clock.millis();
        long maximum;
        try {
            maximum = Math.addExact(now, MAX_DELAY.toMillis());
        } catch (ArithmeticException exception) {
            maximum = Long.MAX_VALUE;
        }
        if (command.deliverAt() <= now || command.deliverAt() > maximum) {
            throw new IngressValidationException("deliverAt must be in the future and within 30 days");
        }
        if (command.payload().length > MAX_PAYLOAD_BYTES) {
            throw new IngressValidationException("payload must not exceed 65536 bytes");
        }
        if (command.sinkType() == null || command.sinkConfig() == null) {
            throw new IngressValidationException("sink type and configuration are required");
        }
        if (command.sinkType() != command.sinkConfig().type()) {
            throw new IngressValidationException("sink configuration must match sink type");
        }
        switch (command.sinkType()) {
            case HTTP -> validateHttp(command.sinkConfig());
            case KAFKA -> validateKafka(command.sinkConfig());
        }
    }

    private static void validateHttp(Object value) {
        if (!(value instanceof HttpSinkConfig config) || config.url() == null || config.url().isBlank()) {
            throw new IngressValidationException("HTTP sink URL is required");
        }
        try {
            URI uri = URI.create(config.url());
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IngressValidationException("HTTP sink URL must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            if (exception instanceof IngressValidationException validation) {
                throw validation;
            }
            throw new IngressValidationException("HTTP sink URL is invalid");
        }
        if (config.timeoutMs() < 0) {
            throw new IngressValidationException("HTTP sink timeout must not be negative");
        }
    }

    private static void validateKafka(Object value) {
        if (!(value instanceof KafkaSinkConfig config)) {
            throw new IngressValidationException("Kafka sink configuration is required");
        }
        if (config.bootstrapServers() == null || config.bootstrapServers().isBlank()) {
            throw new IngressValidationException("Kafka bootstrap servers are required");
        }
        if (config.topic() == null || config.topic().isBlank()) {
            throw new IngressValidationException("Kafka topic is required");
        }
    }

    private static MessageView view(Message message) {
        return new MessageView(
                message.messageId(),
                message.status(),
                message.createdAt(),
                message.deliverAt(),
                message.deliveredAt(),
                message.retryCount(),
                message.lastError(),
                message.sinkType(),
                message.businessTag());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IngressValidationException(field + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
