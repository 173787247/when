package com.when.plugin.storage.redis;

import com.when.core.DeliveryResult;
import com.when.delivery.DeliveryAttempt;
import com.when.delivery.DeliveryLease;
import com.when.delivery.DeliveryStateStore;
import com.when.sink.spi.DeliveryAttemptIds;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/** Redis small-key adapter for delivery leases and the latest twenty attempt records. */
public final class RedisDeliveryStateStore implements DeliveryStateStore {
    private static final int MAX_RECENT_ATTEMPTS = 20;
    private static final String ACQUIRE_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then
              return -1
            end
            local current_until = redis.call('hget', KEYS[2], 'lease_until')
            if current_until and tonumber(current_until) > tonumber(ARGV[1]) then
              return 0
            end
            redis.call('hset', KEYS[2],
              'message_id', ARGV[2],
              'node_id', ARGV[3],
              'attempt_id', ARGV[4],
              'lease_until', ARGV[5])
            local ttl = redis.call('pttl', KEYS[1])
            if ttl < 1 then ttl = tonumber(ARGV[6]) end
            redis.call('pexpire', KEYS[2], ttl)
            return 1
            """;
    private static final String COMPLETE_SCRIPT = """
            if redis.call('hget', KEYS[1], 'attempt_id') == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;
    private static final String APPEND_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then
              return 0
            end
            local ttl = redis.call('pttl', KEYS[1])
            if ttl < 1 then ttl = tonumber(ARGV[1]) end
            redis.call('psetex', KEYS[2], ttl, ARGV[2])
            redis.call('lpush', KEYS[3], ARGV[3])
            redis.call('ltrim', KEYS[3], 0, 19)
            redis.call('pexpire', KEYS[3], ttl)
            return 1
            """;

    private final JedisPool pool;
    private final Clock clock;
    private final long fallbackTtlMillis;
    private final int scanBatchSize;
    private final DeliveryAttemptJsonCodec codec = new DeliveryAttemptJsonCodec();

    public RedisDeliveryStateStore() {
        this(RedisStorageConfig.fromEnvironment());
    }

    public RedisDeliveryStateStore(RedisStorageConfig config) {
        this(config, Clock.systemUTC());
    }

    public RedisDeliveryStateStore(RedisStorageConfig config, Clock clock) {
        this(
                createPool(Objects.requireNonNull(config, "config")),
                clock,
                Math.max(1L, config.terminalRetention().toMillis()),
                config.scanBatchSize());
    }

    RedisDeliveryStateStore(
            JedisPool pool, Clock clock, long fallbackTtlMillis, int scanBatchSize) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (fallbackTtlMillis < 1 || scanBatchSize < 1) {
            throw new IllegalArgumentException("Redis delivery TTL and scan batch size must be positive");
        }
        this.fallbackTtlMillis = fallbackTtlMillis;
        this.scanBatchSize = scanBatchSize;
    }

    @Override
    public Optional<DeliveryLease> tryAcquire(
            String messageId, String nodeId, Instant leaseUntil) {
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        String attemptId = DeliveryAttemptIds.next();
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(
                    ACQUIRE_SCRIPT,
                    List.of(RedisKeys.message(messageId), RedisKeys.deliveryLease(messageId)),
                    List.of(
                            Long.toString(clock.millis()),
                            messageId,
                            requireText(nodeId, "nodeId"),
                            attemptId,
                            Long.toString(leaseUntil.toEpochMilli()),
                            Long.toString(fallbackTtlMillis)));
            if (!Long.valueOf(1L).equals(result)) {
                return Optional.empty();
            }
            return Optional.of(new DeliveryLease(messageId, nodeId, attemptId, leaseUntil));
        } catch (JedisException exception) {
            throw operationFailure("acquire delivery lease", messageId, exception);
        }
    }

    @Override
    public Optional<DeliveryLease> currentLease(String messageId) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> fields = jedis.hgetAll(RedisKeys.deliveryLease(messageId));
            return fields.isEmpty() ? Optional.empty() : Optional.of(toLease(fields));
        } catch (JedisException exception) {
            throw operationFailure("read delivery lease", messageId, exception);
        }
    }

    @Override
    public void complete(String messageId, String attemptId, DeliveryResult result) {
        Objects.requireNonNull(result, "result");
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(
                    COMPLETE_SCRIPT,
                    List.of(RedisKeys.deliveryLease(messageId)),
                    List.of(requireText(attemptId, "attemptId")));
        } catch (JedisException exception) {
            throw operationFailure("complete delivery lease", messageId, exception);
        }
    }

    @Override
    public List<String> findExpiredLeases(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        ScanParams params = new ScanParams()
                .match(RedisKeys.DELIVERY_LEASE_PREFIX + "*")
                .count(scanBatchSize);
        List<String> expired = new ArrayList<>(Math.min(limit, scanBatchSize));
        try (Jedis jedis = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                collectExpired(jedis, scan.getResult(), now.toEpochMilli(), limit, expired);
                cursor = scan.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && expired.size() < limit);
            return List.copyOf(expired);
        } catch (JedisException exception) {
            throw operationFailure("scan expired delivery leases", "batch", exception);
        }
    }

    @Override
    public void appendAttempt(DeliveryAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(
                    APPEND_SCRIPT,
                    List.of(
                            RedisKeys.message(attempt.messageId()),
                            RedisKeys.deliveryAttempt(attempt.messageId(), attempt.attemptId()),
                            RedisKeys.deliveryRecent(attempt.messageId())),
                    List.of(
                            Long.toString(fallbackTtlMillis),
                            codec.encode(attempt),
                            attempt.attemptId()));
            if (!Long.valueOf(1L).equals(result)) {
                throw new RedisStorageException(
                        "Cannot append a delivery attempt for a missing message " + attempt.messageId());
            }
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("append delivery attempt", attempt.messageId(), exception);
        }
    }

    @Override
    public List<DeliveryAttempt> recentAttempts(String messageId, int limit) {
        if (limit < 1 || limit > MAX_RECENT_ATTEMPTS) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        try (Jedis jedis = pool.getResource()) {
            List<String> attemptIds = jedis.lrange(
                    RedisKeys.deliveryRecent(messageId), 0, limit - 1L);
            if (attemptIds.isEmpty()) {
                return List.of();
            }
            List<Response<String>> responses = new ArrayList<>(attemptIds.size());
            try (Pipeline pipeline = jedis.pipelined()) {
                for (String attemptId : attemptIds) {
                    responses.add(pipeline.get(RedisKeys.deliveryAttempt(messageId, attemptId)));
                }
                pipeline.sync();
            }
            List<DeliveryAttempt> attempts = new ArrayList<>(responses.size());
            for (Response<String> response : responses) {
                String encoded = response.get();
                if (encoded != null) {
                    attempts.add(codec.decode(encoded));
                }
            }
            return List.copyOf(attempts);
        } catch (JedisException exception) {
            throw operationFailure("read recent delivery attempts", messageId, exception);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    private static DeliveryLease toLease(Map<String, String> fields) {
        try {
            return new DeliveryLease(
                    fields.get("message_id"),
                    fields.get("node_id"),
                    fields.get("attempt_id"),
                    Instant.ofEpochMilli(Long.parseLong(fields.get("lease_until"))));
        } catch (RuntimeException exception) {
            throw new RedisStorageException("Stored delivery lease is invalid", exception);
        }
    }

    private static void collectExpired(
            Jedis jedis,
            List<String> keys,
            long nowMillis,
            int limit,
            List<String> destination) {
        if (keys.isEmpty() || destination.size() >= limit) {
            return;
        }
        List<Response<String>> responses = new ArrayList<>(keys.size());
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String key : keys) {
                responses.add(pipeline.hget(key, "lease_until"));
            }
            pipeline.sync();
        }
        for (int index = 0; index < keys.size() && destination.size() < limit; index++) {
            String value = responses.get(index).get();
            if (value != null && Long.parseLong(value) <= nowMillis) {
                destination.add(keys.get(index).substring(RedisKeys.DELIVERY_LEASE_PREFIX.length()));
            }
        }
    }

    private static JedisPool createPool(RedisStorageConfig config) {
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectionTimeoutMillis())
                .socketTimeoutMillis(config.connectionTimeoutMillis())
                .clientName("when-delivery-state");
        if (config.password() != null) {
            builder.password(config.password());
        }
        return new JedisPool(new HostAndPort(config.host(), config.port()), builder.build());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static RedisStorageException operationFailure(
            String operation, String identifier, JedisException cause) {
        return new RedisStorageException(
                "Redis " + operation + " failed for identifier " + identifier, cause);
    }
}
