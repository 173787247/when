package com.when.sink.kafka;

import com.when.core.DeliveryResult;
import com.when.core.InvalidConfigException;
import com.when.core.KafkaSinkConfig;
import com.when.core.Message;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import com.when.sink.spi.AttemptAwareSink;
import com.when.observability.TraceOperations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Official Kafka Producer Sink with bounded, fingerprint-keyed Producer reuse. */
public final class KafkaSink implements AttemptAwareSink, AutoCloseable {
    private static final int DEFAULT_MAX_PRODUCERS = 32;
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> RESERVED_HEADERS = List.of(
            "x-when-message-id", "x-when-attempt-id", "x-when-trace-id", "traceparent", "tracestate");

    private final KafkaProducerFactory producerFactory;
    private final KafkaProducerSettingsResolver settingsResolver;
    private final int maxProducers;
    private final long idleTimeoutNanos;
    private final long sendTimeoutMillis;
    private final TraceOperations traces;
    private final Object cacheLock = new Object();
    private final Map<String, CachedProducer> producers = new HashMap<>();
    private boolean closed;

    /** Java-SPI constructor using Kafka settings injected through environment variables. */
    public KafkaSink() {
        this(
                properties -> new KafkaProducer<>(properties),
                KafkaSink::environmentSettings,
                DEFAULT_MAX_PRODUCERS,
                DEFAULT_IDLE_TIMEOUT,
                DEFAULT_SEND_TIMEOUT,
                TraceOperations.noop());
    }

    public KafkaSink(TraceOperations traces) {
        this(
                properties -> new KafkaProducer<>(properties),
                KafkaSink::environmentSettings,
                DEFAULT_MAX_PRODUCERS,
                DEFAULT_IDLE_TIMEOUT,
                DEFAULT_SEND_TIMEOUT,
                traces);
    }

    public KafkaSink(KafkaProducerFactory producerFactory) {
        this(
                producerFactory,
                KafkaSink::baseSettings,
                DEFAULT_MAX_PRODUCERS,
                DEFAULT_IDLE_TIMEOUT,
                DEFAULT_SEND_TIMEOUT,
                TraceOperations.noop());
    }

    public KafkaSink(
            KafkaProducerFactory producerFactory,
            KafkaProducerSettingsResolver settingsResolver,
            int maxProducers,
            Duration idleTimeout,
            Duration sendTimeout) {
        this(
                producerFactory,
                settingsResolver,
                maxProducers,
                idleTimeout,
                sendTimeout,
                TraceOperations.noop());
    }

    public KafkaSink(
            KafkaProducerFactory producerFactory,
            KafkaProducerSettingsResolver settingsResolver,
            int maxProducers,
            Duration idleTimeout,
            Duration sendTimeout,
            TraceOperations traces) {
        this.producerFactory = Objects.requireNonNull(producerFactory, "producerFactory");
        this.settingsResolver = Objects.requireNonNull(settingsResolver, "settingsResolver");
        if (maxProducers < 1) {
            throw new IllegalArgumentException("maxProducers must be positive");
        }
        this.maxProducers = maxProducers;
        this.idleTimeoutNanos = requirePositive(idleTimeout, "idleTimeout").toNanos();
        this.sendTimeoutMillis = requirePositive(sendTimeout, "sendTimeout").toMillis();
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    @Override
    public SinkType type() {
        return SinkType.KAFKA;
    }

    @Override
    public void validateConfig(SinkConfig value) throws InvalidConfigException {
        KafkaSinkConfig config = requireConfig(value);
        requireText(config.bootstrapServers(), "Kafka bootstrap servers");
        requireText(config.topic(), "Kafka topic");
        validateHeaders(config.headers());
        try {
            Map<String, Object> settings = Map.copyOf(settingsResolver.resolve(config));
            requireText(Objects.toString(settings.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG), null),
                    "Kafka bootstrap servers");
        } catch (RuntimeException exception) {
            if (exception instanceof InvalidConfigException invalid) {
                throw invalid;
            }
            throw new InvalidConfigException("Kafka producer configuration is invalid", exception);
        }
    }

    @Override
    public DeliveryResult deliver(Message message, String attemptId) {
        Objects.requireNonNull(message, "message");
        long started = System.nanoTime();
        final KafkaSinkConfig config;
        final ProducerLease lease;
        try {
            config = requireConfig(message.sinkConfig());
            validateConfig(config);
            lease = acquire(config);
        } catch (RuntimeException exception) {
            return classify(exception, started);
        }

        try (lease) {
            RecordHeaders headers = new RecordHeaders();
            config.headers().forEach((name, value) ->
                    headers.add(name, value.getBytes(StandardCharsets.UTF_8)));
            headers.add("X-When-Message-Id", requireText(message.messageId(), "messageId")
                    .getBytes(StandardCharsets.UTF_8));
            headers.add("X-When-Attempt-Id", requireText(attemptId, "attemptId")
                    .getBytes(StandardCharsets.UTF_8));
            headers.add("X-When-Trace-Id", safeTrace(message.traceId()).getBytes(StandardCharsets.UTF_8));
            traces.injectCurrentContext().forEach((name, value) ->
                    headers.add(name, value.getBytes(StandardCharsets.UTF_8)));
            String key = config.key() == null || config.key().isBlank() ? message.messageId() : config.key();
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    config.topic(), null, key, payload(message), headers);
            lease.producer().send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            return DeliveryResult.success(elapsed(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.retryableFailure(
                    "KAFKA_INTERRUPTED", "Kafka delivery was interrupted", elapsed(started));
        } catch (ExecutionException exception) {
            return classify(exception.getCause(), started);
        } catch (TimeoutException exception) {
            return DeliveryResult.retryableFailure(
                    "KAFKA_TIMEOUT", "Kafka delivery timed out", elapsed(started));
        } catch (RuntimeException exception) {
            return classify(exception, started);
        }
    }

    @Override
    public boolean healthCheck() {
        synchronized (cacheLock) {
            return !closed;
        }
    }

    public int cachedProducerCount() {
        synchronized (cacheLock) {
            return producers.size();
        }
    }

    public void evictIdle() {
        List<Producer<String, byte[]>> evicted = new ArrayList<>();
        synchronized (cacheLock) {
            evictIdleLocked(System.nanoTime(), evicted);
        }
        evicted.forEach(KafkaSink::closeProducer);
    }

    @Override
    public void close() {
        List<Producer<String, byte[]>> closing;
        synchronized (cacheLock) {
            if (closed) {
                return;
            }
            closed = true;
            closing = producers.values().stream().map(entry -> entry.producer).toList();
            producers.clear();
        }
        for (Producer<String, byte[]> producer : closing) {
            try {
                producer.flush();
            } finally {
                closeProducer(producer);
            }
        }
    }

    private ProducerLease acquire(KafkaSinkConfig config) {
        Map<String, Object> properties = Map.copyOf(settingsResolver.resolve(config));
        String fingerprint = fingerprint(properties);
        List<Producer<String, byte[]>> evicted = new ArrayList<>();
        CachedProducer selected;
        synchronized (cacheLock) {
            if (closed) {
                throw new IllegalStateException("Kafka Sink is closed");
            }
            long now = System.nanoTime();
            evictIdleLocked(now, evicted);
            selected = producers.get(fingerprint);
            if (selected == null) {
                if (producers.size() >= maxProducers) {
                    throw new ConfigException("Kafka Producer cache capacity reached");
                }
                selected = new CachedProducer(producerFactory.create(properties), now);
                producers.put(fingerprint, selected);
            }
            selected.active++;
            selected.lastUsedNanos = now;
        }
        evicted.forEach(KafkaSink::closeProducer);
        return new ProducerLease(selected);
    }

    private void release(CachedProducer cached) {
        synchronized (cacheLock) {
            if (cached.active > 0) {
                cached.active--;
            }
            cached.lastUsedNanos = System.nanoTime();
        }
    }

    private void evictIdleLocked(long now, List<Producer<String, byte[]>> destination) {
        var iterator = producers.entrySet().iterator();
        while (iterator.hasNext()) {
            CachedProducer cached = iterator.next().getValue();
            if (cached.active == 0 && now - cached.lastUsedNanos >= idleTimeoutNanos) {
                iterator.remove();
                destination.add(cached.producer);
            }
        }
    }

    private static Map<String, Object> baseSettings(KafkaSinkConfig config) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return properties;
    }

    private static Map<String, Object> environmentSettings(KafkaSinkConfig config) {
        Map<String, Object> properties = new HashMap<>(baseSettings(config));
        putEnvironment(properties, CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "WHEN_KAFKA_SECURITY_PROTOCOL");
        putEnvironment(properties, "sasl.mechanism", "WHEN_KAFKA_SASL_MECHANISM");
        putEnvironment(properties, "sasl.jaas.config", "WHEN_KAFKA_SASL_JAAS_CONFIG");
        return properties;
    }

    private static void putEnvironment(Map<String, Object> properties, String property, String environmentName) {
        String value = System.getenv(environmentName);
        if (value != null && !value.isBlank()) {
            properties.put(property, value);
        }
    }

    private static String fingerprint(Map<String, Object> properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            properties.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        update(digest, entry.getKey());
                        update(digest, Objects.toString(entry.getValue(), ""));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static DeliveryResult classify(Throwable failure, long started) {
        Throwable cause = failure == null ? new KafkaException("unknown Kafka failure") : failure;
        long duration = elapsed(started);
        if (cause instanceof org.apache.kafka.common.errors.TimeoutException
                || cause instanceof RetriableException) {
            return DeliveryResult.retryableFailure("KAFKA_TIMEOUT", "Kafka delivery failed temporarily", duration);
        }
        if (cause instanceof AuthenticationException) {
            return DeliveryResult.permanentFailure("KAFKA_AUTH", "Kafka authentication failed", duration);
        }
        if (cause instanceof AuthorizationException) {
            return DeliveryResult.permanentFailure("KAFKA_AUTHORIZATION", "Kafka authorization failed", duration);
        }
        if (cause instanceof SerializationException) {
            return DeliveryResult.permanentFailure("KAFKA_SERIALIZATION", "Kafka serialization failed", duration);
        }
        if (cause instanceof InvalidTopicException) {
            return DeliveryResult.permanentFailure("KAFKA_TOPIC", "Kafka topic is invalid", duration);
        }
        if (cause instanceof ConfigException || cause instanceof InvalidConfigException
                || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return DeliveryResult.permanentFailure("KAFKA_CONFIG", "Kafka configuration is invalid", duration);
        }
        return DeliveryResult.retryableFailure("KAFKA_CLIENT", "Kafka client delivery failed", duration);
    }

    private static KafkaSinkConfig requireConfig(SinkConfig value) {
        if (!(value instanceof KafkaSinkConfig config)) {
            throw new InvalidConfigException("Kafka Sink requires KafkaSinkConfig");
        }
        return config;
    }

    private static void validateHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            if (name == null || name.isBlank() || header.getValue() == null) {
                throw new InvalidConfigException("Kafka header is invalid");
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidConfigException("Kafka header conflicts with a reserved header");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(field + " must not be blank");
        }
        return value;
    }

    private static byte[] payload(Message message) {
        byte[] value = message.payload();
        return value == null ? new byte[0] : value;
    }

    private static String safeTrace(String traceId) {
        return traceId == null || traceId.isBlank() ? "-" : traceId;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void closeProducer(Producer<String, byte[]> producer) {
        producer.close(Duration.ofSeconds(5));
    }

    private final class ProducerLease implements AutoCloseable {
        private final CachedProducer cached;
        private boolean released;

        private ProducerLease(CachedProducer cached) {
            this.cached = cached;
        }

        Producer<String, byte[]> producer() {
            return cached.producer;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                release(cached);
            }
        }
    }

    private static final class CachedProducer {
        private final Producer<String, byte[]> producer;
        private long lastUsedNanos;
        private int active;

        private CachedProducer(Producer<String, byte[]> producer, long lastUsedNanos) {
            this.producer = Objects.requireNonNull(producer, "producer");
            this.lastUsedNanos = lastUsedNanos;
        }
    }
}
