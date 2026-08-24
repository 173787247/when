package com.when.plugin.storage.redis;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/** Redis implementation of the common message-fact storage contract. */
public final class RedisStoragePlugin implements StoragePlugin, AutoCloseable {
    private static final int MAX_TRANSITION_ATTEMPTS = 128;
    private static final String CREATE_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 1 then
              return 0
            end
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            redis.call('psetex', KEYS[2], ARGV[1], ARGV[3])
            return 1
            """;

    private final JedisPool pool;
    private final Duration terminalRetention;
    private final Clock clock;
    private final int scanBatchSize;
    private final MessageJsonCodec codec;

    /** Creates the SPI implementation using the documented WHEN_REDIS_* environment variables. */
    public RedisStoragePlugin() {
        this(RedisStorageConfig.fromEnvironment());
    }

    public RedisStoragePlugin(RedisStorageConfig config) {
        this(config, Clock.systemUTC());
    }

    public RedisStoragePlugin(RedisStorageConfig config, Clock clock) {
        this(createPool(config), config.terminalRetention(), clock, config.scanBatchSize());
    }

    RedisStoragePlugin(JedisPool pool, Duration terminalRetention, Clock clock, int scanBatchSize) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.terminalRetention = Objects.requireNonNull(terminalRetention, "terminalRetention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (terminalRetention.isNegative() || scanBatchSize < 1) {
            throw new IllegalArgumentException("Retention must not be negative and scan batch size must be positive");
        }
        this.scanBatchSize = scanBatchSize;
        this.codec = new MessageJsonCodec();
    }

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public void create(Message message) {
        validateNewMessage(message);
        String messageKey = RedisKeys.message(message.messageId());
        String indexKey = RedisKeys.scheduleIndex(message.timeWheelId(), message.messageId());
        long ttlMillis = ttlMillis(message);
        try (Jedis jedis = pool.getResource(); Pipeline pipeline = jedis.pipelined()) {
            Response<Object> result = pipeline.eval(
                    CREATE_SCRIPT,
                    List.of(messageKey, indexKey),
                    List.of(Long.toString(ttlMillis), codec.encode(message), Long.toString(message.nextAttemptAt())));
            pipeline.sync();
            if (!Long.valueOf(1L).equals(result.get())) {
                throw new RedisStorageException("Message already exists: " + message.messageId());
            }
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("create", message.messageId(), exception);
        }
    }

    @Override
    public Optional<Message> get(String messageId) {
        String key = RedisKeys.message(messageId);
        try (Jedis jedis = pool.getResource()) {
            String encoded = jedis.get(key);
            return encoded == null ? Optional.empty() : Optional.of(codec.decode(encoded));
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("get", messageId, exception);
        }
    }

    @Override
    public boolean transition(
            String messageId,
            MessageStatus expected,
            MessageStatus target,
            StatePatch patch) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException("Illegal message status transition: " + expected + " -> " + target);
        }
        StatePatch effectivePatch = patch == null ? new StatePatch(null, null, null, null) : patch;
        String messageKey = RedisKeys.message(messageId);

        try (Jedis jedis = pool.getResource()) {
            for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
                jedis.watch(messageKey);
                String encoded = jedis.get(messageKey);
                if (encoded == null) {
                    jedis.unwatch();
                    return false;
                }

                Message current = codec.decode(encoded);
                if (current.status() != expected) {
                    jedis.unwatch();
                    return false;
                }

                Message updated = apply(current, target, effectivePatch);
                String indexKey = RedisKeys.scheduleIndex(updated.timeWheelId(), updated.messageId());
                long existingTtl = jedis.pttl(messageKey);
                long expiration = expirationAtForWrite(updated);
                if (existingTtl > 0) {
                    expiration = Math.max(expiration, safeAdd(clock.millis(), existingTtl));
                }

                Transaction transaction = jedis.multi();
                transaction.set(messageKey, codec.encode(updated));
                transaction.pexpireAt(messageKey, expiration);
                if (target == MessageStatus.PENDING) {
                    transaction.set(indexKey, Long.toString(updated.nextAttemptAt()));
                    transaction.pexpireAt(indexKey, expiration);
                } else {
                    transaction.del(indexKey);
                }
                List<Object> result = transaction.exec();
                if (result != null) {
                    return true;
                }
            }
            throw new RedisStorageException("Transition contention exceeded retry limit for message " + messageId);
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("transition", messageId, exception);
        }
    }

    @Override
    public List<Message> loadPendingByTimeWheel(String timeWheelId) {
        String indexPrefix = RedisKeys.scheduleIndexPrefix(timeWheelId);
        ScanParams scanParams = new ScanParams().match(indexPrefix + "*").count(scanBatchSize);
        List<Message> pending = new ArrayList<>();
        Set<String> loadedMessageIds = new HashSet<>();
        try (Jedis jedis = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, scanParams);
                loadBatch(jedis, indexPrefix, scan.getResult(), loadedMessageIds, pending);
                cursor = scan.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("load pending", timeWheelId, exception);
        }
        pending.sort(Comparator.comparingLong(Message::nextAttemptAt).thenComparing(Message::messageId));
        return List.copyOf(pending);
    }

    @Override
    public void deleteExpired(String messageId) {
        String messageKey = RedisKeys.message(messageId);
        try (Jedis jedis = pool.getResource()) {
            String encoded = jedis.get(messageKey);
            if (encoded == null) {
                return;
            }
            Message message = codec.decode(encoded);
            if (!isTerminal(message.status()) || clock.millis() < retentionDeadline(message)) {
                return;
            }
            String indexKey = RedisKeys.scheduleIndex(message.timeWheelId(), message.messageId());
            try (Pipeline pipeline = jedis.pipelined()) {
                pipeline.del(messageKey, indexKey);
                pipeline.sync();
            }
        } catch (RedisStorageException exception) {
            throw exception;
        } catch (JedisException exception) {
            throw operationFailure("delete expired", messageId, exception);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    /** Bounded dependency probe for readiness; it does not read or expose message data. */
    public boolean healthCheck() {
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (JedisException failure) {
            return false;
        }
    }

    private void loadBatch(
            Jedis jedis,
            String indexPrefix,
            List<String> indexKeys,
            Set<String> loadedMessageIds,
            List<Message> destination) {
        if (indexKeys.isEmpty()) {
            return;
        }
        List<Response<String>> responses = new ArrayList<>(indexKeys.size());
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String indexKey : indexKeys) {
                String messageId = indexKey.substring(indexPrefix.length());
                if (loadedMessageIds.add(messageId)) {
                    responses.add(pipeline.get(RedisKeys.message(messageId)));
                }
            }
            pipeline.sync();
            for (Response<String> response : responses) {
                String encoded = response.get();
                if (encoded != null) {
                    Message message = codec.decode(encoded);
                    if (message.status() == MessageStatus.PENDING) {
                        destination.add(message);
                    }
                }
            }
        }
    }

    private Message apply(Message current, MessageStatus target, StatePatch patch) {
        return new Message(
                current.messageId(),
                current.createdAt(),
                current.deliverAt(),
                current.timeWheelId(),
                current.sinkType(),
                current.sinkConfig(),
                current.payload(),
                current.businessTag(),
                target,
                patch.retryCount() == null ? current.retryCount() : patch.retryCount(),
                patch.nextAttemptAt() == null ? current.nextAttemptAt() : patch.nextAttemptAt(),
                patch.deliveredAt() == null ? current.deliveredAt() : patch.deliveredAt(),
                patch.lastError() == null ? current.lastError() : patch.lastError(),
                current.traceId());
    }

    private long ttlMillis(Message message) {
        return Math.max(1L, expirationAtForWrite(message) - clock.millis());
    }

    private long expirationAtForWrite(Message message) {
        return Math.max(
                retentionDeadline(message),
                safeAdd(clock.millis(), terminalRetention.toMillis()));
    }

    private long retentionDeadline(Message message) {
        long latestKnownTime = Math.max(
                message.deliverAt(), Math.max(message.nextAttemptAt(), message.deliveredAt()));
        return safeAdd(latestKnownTime, terminalRetention.toMillis());
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isTerminal(MessageStatus status) {
        return status == MessageStatus.DELIVERED
                || status == MessageStatus.FAILED
                || status == MessageStatus.CANCELLED;
    }

    private static void validateNewMessage(Message message) {
        Objects.requireNonNull(message, "message");
        RedisKeys.message(message.messageId());
        RedisKeys.scheduleIndex(message.timeWheelId(), message.messageId());
        Objects.requireNonNull(message.sinkType(), "sinkType");
        Objects.requireNonNull(message.sinkConfig(), "sinkConfig");
        if (message.sinkType() != message.sinkConfig().type()) {
            throw new IllegalArgumentException("Sink type and sink configuration do not match");
        }
        if (message.status() != MessageStatus.PENDING) {
            throw new IllegalArgumentException("A newly created message must be PENDING");
        }
    }

    private static JedisPool createPool(RedisStorageConfig config) {
        Objects.requireNonNull(config, "config");
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectionTimeoutMillis())
                .socketTimeoutMillis(config.connectionTimeoutMillis())
                .clientName("when-storage-redis");
        if (config.password() != null) {
            builder.password(config.password());
        }
        return new JedisPool(new HostAndPort(config.host(), config.port()), builder.build());
    }

    private static RedisStorageException operationFailure(
            String operation, String identifier, JedisException cause) {
        return new RedisStorageException(
                "Redis " + operation + " failed for identifier " + identifier, cause);
    }
}
