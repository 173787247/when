package com.when.plugin.storage.redis;

import com.when.observability.TraceContextSnapshot;
import com.when.observability.TraceContextStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

/** Per-message Redis store for the W3C submit context used by a later Delivery Span Link. */
public final class RedisTraceContextStore implements TraceContextStore, AutoCloseable {
    private final JedisPool pool;
    private final long ttlMillis;

    public RedisTraceContextStore(RedisStorageConfig config) {
        Objects.requireNonNull(config, "config");
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectionTimeoutMillis())
                .socketTimeoutMillis(config.connectionTimeoutMillis())
                .clientName("when-trace-context");
        if (config.password() != null) {
            builder.password(config.password());
        }
        this.pool = new JedisPool(new HostAndPort(config.host(), config.port()), builder.build());
        this.ttlMillis = Math.max(Duration.ofDays(31).toMillis(), config.terminalRetention().toMillis());
    }

    @Override
    public void put(String messageId, TraceContextSnapshot context) {
        Objects.requireNonNull(context, "context");
        if (!context.valid()) {
            return;
        }
        String state = context.tracestate() == null ? "" : context.tracestate();
        if (containsLineBreak(context.traceparent()) || containsLineBreak(state)) {
            throw new IllegalArgumentException("W3C trace context must not contain line breaks");
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.psetex(RedisKeys.traceContext(messageId), ttlMillis, context.traceparent() + "\n" + state);
        } catch (JedisException failure) {
            throw new RedisStorageException("Redis trace-context put failed", failure);
        }
    }

    @Override
    public Optional<TraceContextSnapshot> get(String messageId) {
        try (Jedis jedis = pool.getResource()) {
            String encoded = jedis.get(RedisKeys.traceContext(messageId));
            if (encoded == null) {
                return Optional.empty();
            }
            int split = encoded.indexOf('\n');
            String traceparent = split < 0 ? encoded : encoded.substring(0, split);
            String tracestate = split < 0 || split == encoded.length() - 1
                    ? null
                    : encoded.substring(split + 1);
            return Optional.of(new TraceContextSnapshot(
                    traceparent, tracestate, null, null, traceparent.endsWith("-01")));
        } catch (JedisException failure) {
            throw new RedisStorageException("Redis trace-context get failed", failure);
        }
    }

    @Override
    public void delete(String messageId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(RedisKeys.traceContext(messageId));
        } catch (JedisException failure) {
            throw new RedisStorageException("Redis trace-context delete failed", failure);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
