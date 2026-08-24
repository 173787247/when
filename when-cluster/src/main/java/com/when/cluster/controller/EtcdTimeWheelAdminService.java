package com.when.cluster.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Durable 24-hour idempotency and Controller delegation for time-wheel management. */
public final class EtcdTimeWheelAdminService implements TimeWheelAdminService {
    private static final long RETENTION_SECONDS = Duration.ofHours(24).toSeconds();
    private final Controller controller;
    private final EtcdMetadataClient client;
    private final MetadataJsonCodec metadataCodec = new MetadataJsonCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public EtcdTimeWheelAdminService(Controller controller, EtcdMetadataClient client) {
        this(controller, client, Clock.systemUTC());
    }

    EtcdTimeWheelAdminService(Controller controller, EtcdMetadataClient client, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<TimeWheelAssignmentView> list() {
        List<TimeWheelAssignmentView> views = new ArrayList<>();
        client.getPrefix(EtcdKeys.TIME_WHEELS_PREFIX).forEach((key, value) -> {
            String twId = key.substring(EtcdKeys.TIME_WHEELS_PREFIX.length());
            TimeWheelMetadata metadata = metadataCodec.decodeTimeWheel(value);
            views.add(view(twId, metadata));
        });
        views.sort(Comparator.comparing(TimeWheelAssignmentView::twId));
        return List.copyOf(views);
    }

    @Override
    public CreationResult create(int count, String idempotencyKey) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("count must be between 1 and 100");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("idempotency key is invalid");
        }
        String keyHash = sha256(idempotencyKey);
        String requestHash = sha256("count=" + count);
        String key = EtcdKeys.timeWheelIdempotency(keyHash);
        String encoded = client.get(key).orElse(null);
        if (encoded != null) {
            StoredOperation existing = decode(encoded);
            ensureSameRequest(existing, requestHash);
            if ("completed".equals(existing.status())) return existing.result(true);
            throw new OperationConflictException();
        }

        long leaseId = client.grantLease(RETENTION_SECONDS);
        StoredOperation pending = new StoredOperation(
                requestHash, "in_progress", clock.millis(), List.of(), List.of());
        String pendingJson = encode(pending);
        if (!client.txnPutIfAbsent(key, pendingJson, leaseId)) {
            client.revokeLease(leaseId);
            StoredOperation concurrent = client.get(key).map(this::decode)
                    .orElseThrow(OperationConflictException::new);
            ensureSameRequest(concurrent, requestHash);
            if ("completed".equals(concurrent.status())) return concurrent.result(true);
            throw new OperationConflictException();
        }

        try {
            List<String> created = new ArrayList<>(count);
            List<TimeWheelAssignmentView> assignments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String twId = "tw-" + keyHash.substring(0, 12) + "-" + index;
                assignments.add(controller.createTimeWheel(new TimeWheelSpec(twId)));
                created.add(twId);
            }
            StoredOperation completed = new StoredOperation(
                    requestHash, "completed", pending.createdAt(), created, assignments);
            if (!client.txnPutIfValue(key, pendingJson, encode(completed), leaseId)) {
                throw new OperationConflictException();
            }
            return completed.result(false);
        } catch (RuntimeException failure) {
            // Keep the in-progress marker. Deterministic tw_ids make an operator retry safe after
            // the 24-hour retention window, while concurrent retries receive a stable conflict.
            throw failure;
        }
    }

    private static TimeWheelAssignmentView view(String twId, TimeWheelMetadata metadata) {
        return new TimeWheelAssignmentView(
                twId, metadata.master(), metadata.slave(), metadata.status(), metadata.syncState(),
                metadata.assignmentVersion());
    }

    private static void ensureSameRequest(StoredOperation operation, String requestHash) {
        if (!operation.requestHash().equals(requestHash)) throw new IdempotencyConflictException();
    }

    private String encode(StoredOperation operation) {
        try {
            return mapper.writeValueAsString(operation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("time-wheel operation could not be encoded", exception);
        }
    }

    private StoredOperation decode(String value) {
        try {
            return mapper.readValue(value, StoredOperation.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored time-wheel operation is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record StoredOperation(
            String requestHash,
            String status,
            long createdAt,
            List<String> created,
            List<TimeWheelAssignmentView> assignments) {
        private StoredOperation {
            Objects.requireNonNull(requestHash, "requestHash");
            Objects.requireNonNull(status, "status");
            created = List.copyOf(created);
            assignments = List.copyOf(assignments);
        }

        private CreationResult result(boolean replayed) {
            return new CreationResult(created, assignments, replayed);
        }
    }
}
