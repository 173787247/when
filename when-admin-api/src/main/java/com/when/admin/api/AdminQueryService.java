package com.when.admin.api;

import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageView;
import com.when.api.http.BusinessHttpController;
import com.when.api.http.HttpApiException;
import com.when.api.http.generated.model.*;
import com.when.cluster.controller.TimeWheelAdminService;
import com.when.cluster.controller.TimeWheelAssignmentView;
import com.when.cluster.membership.ClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.delivery.DeliveryAttempt;
import com.when.delivery.DeliveryStateStore;
import com.when.plugin.storage.redis.RedisAdminQueryStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Aggregates existing domain services into safe management projections. */
public final class AdminQueryService {
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    private final DelayMessageHandler messages;
    private final RedisAdminQueryStore index;
    private final DeliveryStateStore attempts;
    private final TimeWheelAdminService timeWheels;
    private final ClusterMembership membership;
    private final Clock clock;

    public AdminQueryService(
            DelayMessageHandler messages,
            RedisAdminQueryStore index,
            DeliveryStateStore attempts,
            TimeWheelAdminService timeWheels,
            ClusterMembership membership) {
        this(messages, index, attempts, timeWheels, membership, Clock.systemUTC());
    }

    AdminQueryService(
            DelayMessageHandler messages,
            RedisAdminQueryStore index,
            DeliveryStateStore attempts,
            TimeWheelAdminService timeWheels,
            ClusterMembership membership,
            Clock clock) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.index = Objects.requireNonNull(index, "index");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ItemsData<TimeWheelView> listTimeWheels() {
        return new ItemsData<>(timeWheels.list().stream().map(this::timeWheel).toList());
    }

    public CreatedTimeWheels createTimeWheels(int count, String idempotencyKey) {
        try {
            TimeWheelAdminService.CreationResult result = timeWheels.create(count, idempotencyKey);
            List<TimeWheelView> assignments = result.assignments().stream().map(this::timeWheel).toList();
            return new CreatedTimeWheels(
                    new CreateTimeWheelsData(result.created(), assignments), result.replayed());
        } catch (TimeWheelAdminService.IdempotencyConflictException conflict) {
            throw new HttpApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                    "idempotency key conflicts with an earlier request");
        } catch (TimeWheelAdminService.OperationConflictException conflict) {
            throw new HttpApiException(HttpStatus.CONFLICT, "TIMEWHEEL_OPERATION_CONFLICT",
                    "time-wheel operation is already in progress");
        } catch (IllegalStateException unavailable) {
            throw new HttpApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTROLLER_UNAVAILABLE",
                    "Controller is temporarily unavailable");
        }
    }

    public MessagePageData listMessages(
            MessageStatus status,
            SinkType sinkType,
            String tag,
            Long from,
            Long to,
            String cursor,
            int limit) {
        long upper = to == null ? clock.millis() : to;
        long lower = from == null ? Math.max(0L, upper - DEFAULT_RANGE.toMillis()) : from;
        try {
            RedisAdminQueryStore.AdminMessagePage page = index.query(
                    new RedisAdminQueryStore.AdminMessageQuery(
                            status == null ? null : com.when.core.MessageStatus.valueOf(status.name()),
                            sinkType == null ? null : com.when.core.SinkType.valueOf(sinkType.name()),
                            tag, lower, upper, cursor, limit));
            return new MessagePageData(
                    page.items().stream().map(this::summary).toList(),
                    page.nextCursor(), page.hasMore(), page.indexUpdatedAt());
        } catch (RedisAdminQueryStore.InvalidAdminTimeRangeException invalid) {
            throw new HttpApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE",
                    "query time range must not exceed 7 days",
                    java.util.Map.of("field", "from,to", "max_range_days", 7));
        } catch (RedisAdminQueryStore.InvalidAdminCursorException invalid) {
            throw new HttpApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor is invalid");
        }
    }

    public MessageDetails details(String messageId) {
        MessageView view = messages.query(messageId);
        List<DeliveryAttemptView> history = attempts.recentAttempts(messageId, 20).stream()
                .map(AdminQueryService::attempt)
                .toList();
        MessageSummary summary = BusinessHttpController.summary(view);
        return new MessageDetails(
                summary.messageId(), summary.status(), summary.createdAt(), summary.deliverAt(),
                summary.deliveredAt(), summary.sinkType(), summary.businessTag(), summary.retryCount(),
                summary.lastError(), history);
    }

    public ItemsData<ClusterNodeView> listNodes() {
        String controller = membership.currentController().orElse(null);
        return new ItemsData<>(membership.listNodes().stream()
                .map(node -> node(node, Objects.equals(controller, node.nodeId())))
                .toList());
    }

    private MessageSummary summary(com.when.core.Message message) {
        return new MessageSummary(
                message.messageId(), MessageStatus.valueOf(message.status().name()),
                message.createdAt(), message.deliverAt(), message.deliveredAt(),
                SinkType.valueOf(message.sinkType().name()), message.businessTag(),
                message.retryCount(), sanitize(message.lastError(), 512));
    }

    private TimeWheelView timeWheel(TimeWheelAssignmentView assignment) {
        return new TimeWheelView(
                assignment.twId(), assignment.master(), assignment.slave(), assignment.status(),
                assignment.syncState(), assignment.assignmentVersion(),
                index.pendingMessageCount(assignment.twId()));
    }

    private static DeliveryAttemptView attempt(DeliveryAttempt attempt) {
        String result = attempt.success()
                ? "SUCCESS"
                : attempt.retryable() ? "RETRYABLE_FAILURE" : "TERMINAL_FAILURE";
        return new DeliveryAttemptView(
                attempt.attemptId(), attempt.startedAt().toEpochMilli(), attempt.endedAt().toEpochMilli(),
                result, sanitize(attempt.errorCode(), 128), null, attempt.durationMs());
    }

    private static ClusterNodeView node(NodeInfo node, boolean controller) {
        return new ClusterNodeView(
                node.nodeId(), node.ip(), node.grpcPort(), node.startTime(), node.load(), true, controller);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record CreatedTimeWheels(CreateTimeWheelsData data, boolean replayed) {
    }
}
