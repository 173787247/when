package com.when.plugin.storage.redis;

import com.when.api.http.AdminIndexWriter;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.resps.Tuple;

/**
 * Best-effort, bounded Redis index used only by management list queries. Message facts remain in
 * {@link RedisStoragePlugin}; this index is never consulted by scheduling or recovery.
 */
public final class RedisAdminQueryStore implements AdminIndexWriter, AutoCloseable {
    public static final int SHARDS = 16;
    public static final int MAX_PART_SIZE = 10_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final int MAX_REPAIR_QUEUE = 10_000;
    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final String INDEX_SCRIPT = """
            local part = tonumber(redis.call('get', KEYS[1]) or '0')
            local index_key = ARGV[1] .. part
            if redis.call('zcard', index_key) >= tonumber(ARGV[4]) then
              part = part + 1
              index_key = ARGV[1] .. part
              redis.call('set', KEYS[1], tostring(part), 'PX', ARGV[5])
            end
            redis.call('zadd', index_key, ARGV[2], ARGV[3])
            redis.call('pexpire', index_key, ARGV[5])
            redis.call('pexpire', KEYS[1], ARGV[5])
            return part
            """;

    private final JedisPool pool;
    private final RedisStoragePlugin storage;
    private final Clock clock;
    private final long ttlMillis;
    private final Queue<PendingIndex> repairs = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService repairExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long indexUpdatedAt;

    public RedisAdminQueryStore(RedisStorageConfig config, RedisStoragePlugin storage) {
        this(createPool(config), storage, Clock.systemUTC(), config.terminalRetention());
    }

    RedisAdminQueryStore(
            JedisPool pool, RedisStoragePlugin storage, Clock clock, Duration retention) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttlMillis = Math.max(Duration.ofDays(7).toMillis(), retention.toMillis());
        this.repairExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-admin-index-repair");
            thread.setDaemon(true);
            return thread;
        });
        this.repairExecutor.scheduleWithFixedDelay(this::repairSafely, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void index(String messageId, long deliverAt) {
        PendingIndex pending = new PendingIndex(requireText(messageId, "messageId"), deliverAt);
        try {
            write(pending);
        } catch (RuntimeException failure) {
            if (repairs.size() < MAX_REPAIR_QUEUE) {
                repairs.offer(pending);
            }
        }
    }

    /** Retries the bounded in-memory backlog. A failed retry remains queued for the next run. */
    public int repairNow() {
        int repaired = 0;
        int attempts = repairs.size();
        for (int index = 0; index < attempts; index++) {
            PendingIndex pending = repairs.poll();
            if (pending == null) break;
            try {
                write(pending);
                repaired++;
            } catch (RuntimeException failure) {
                repairs.offer(pending);
            }
        }
        return repaired;
    }

    public AdminMessagePage query(AdminMessageQuery query) {
        Objects.requireNonNull(query, "query");
        Cursor after = decodeCursor(query);
        List<Candidate> candidates = candidates(query, after);
        candidates.sort(Comparator.comparingLong(Candidate::deliverAt)
                .thenComparing(Candidate::messageId));

        List<Message> items = new ArrayList<>(query.limit());
        Candidate lastVisited = null;
        boolean hasMore = false;
        for (Candidate candidate : candidates) {
            if (!after.before(candidate)) continue;
            Optional<Message> message = storage.get(candidate.messageId());
            if (message.isEmpty() || !matches(message.orElseThrow(), query)) continue;
            if (items.size() == query.limit()) {
                hasMore = true;
                break;
            }
            items.add(message.orElseThrow());
            lastVisited = candidate;
        }
        String next = hasMore && lastVisited != null ? encodeCursor(query, lastVisited) : null;
        return new AdminMessagePage(items, next, hasMore, indexUpdatedAt);
    }

    public long pendingMessageCount(String timeWheelId) {
        return storage.loadPendingByTimeWheel(timeWheelId).size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        repairExecutor.shutdownNow();
        pool.close();
    }

    private List<Candidate> candidates(AdminMessageQuery query, Cursor after) {
        List<Candidate> result = new ArrayList<>();
        long firstHour = Math.floorDiv(query.from(), HOUR_MILLIS) * HOUR_MILLIS;
        int perPartLimit = Math.min(1_000, Math.max(query.limit() * 10, 100));
        try (Jedis jedis = pool.getResource()) {
            for (long hourStart = firstHour; hourStart <= query.to(); hourStart += HOUR_MILLIS) {
                String hour = HOUR.format(Instant.ofEpochMilli(hourStart));
                for (int shard = 0; shard < SHARDS; shard++) {
                    String encodedParts = jedis.get(RedisKeys.adminIndexParts(hour, shard));
                    int lastPart = encodedParts == null ? 0 : parsePart(encodedParts);
                    for (int part = 0; part <= lastPart; part++) {
                        List<Tuple> tuples = jedis.zrangeByScoreWithScores(
                                RedisKeys.adminIndex(hour, shard, part),
                                query.from(), query.to(), 0, perPartLimit);
                        for (Tuple tuple : tuples) {
                            Candidate candidate = new Candidate(tuple.getElement(), (long) tuple.getScore());
                            if (after.before(candidate)) result.add(candidate);
                        }
                    }
                }
            }
            return result;
        } catch (JedisException exception) {
            throw new RedisStorageException("Redis admin index query failed", exception);
        }
    }

    private void write(PendingIndex pending) {
        String hour = HOUR.format(Instant.ofEpochMilli(pending.deliverAt()));
        int shard = Math.floorMod(pending.messageId().hashCode(), SHARDS);
        String partPrefix = RedisKeys.ADMIN_INDEX_PREFIX + hour + ":" + shard + ":";
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(
                    INDEX_SCRIPT,
                    List.of(RedisKeys.adminIndexParts(hour, shard)),
                    List.of(partPrefix, Long.toString(pending.deliverAt()), pending.messageId(),
                            Integer.toString(MAX_PART_SIZE), Long.toString(ttlMillis)));
            indexUpdatedAt = clock.millis();
        } catch (JedisException exception) {
            throw new RedisStorageException("Redis admin index write failed", exception);
        }
    }

    private void repairSafely() {
        try {
            repairNow();
        } catch (RuntimeException ignored) {
            // The bounded queue retains work; this best-effort index never affects delivery.
        }
    }

    private static boolean matches(Message message, AdminMessageQuery query) {
        return (query.status() == null || message.status() == query.status())
                && (query.sinkType() == null || message.sinkType() == query.sinkType())
                && (query.businessTag() == null || query.businessTag().equals(message.businessTag()))
                && message.deliverAt() >= query.from() && message.deliverAt() <= query.to();
    }

    private static String encodeCursor(AdminMessageQuery query, Candidate candidate) {
        String raw = "v1|" + query.from() + "|" + query.to() + "|" + fingerprint(query)
                + "|" + candidate.deliverAt() + "|" + candidate.messageId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(AdminMessageQuery query) {
        if (query.cursor() == null) return Cursor.START;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(query.cursor()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 6);
            if (parts.length != 6 || !"v1".equals(parts[0])
                    || Long.parseLong(parts[1]) != query.from()
                    || Long.parseLong(parts[2]) != query.to()
                    || !parts[3].equals(fingerprint(query))) {
                throw new IllegalArgumentException("cursor does not match query");
            }
            return new Cursor(Long.parseLong(parts[4]), requireText(parts[5], "cursor messageId"));
        } catch (RuntimeException invalid) {
            throw new InvalidAdminCursorException();
        }
    }

    private static String fingerprint(AdminMessageQuery query) {
        String input = Objects.toString(query.status(), "") + "|"
                + Objects.toString(query.sinkType(), "") + "|"
                + Objects.toString(query.businessTag(), "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int parsePart(String encoded) {
        try {
            int part = Integer.parseInt(encoded);
            if (part < 0) throw new NumberFormatException();
            return part;
        } catch (NumberFormatException invalid) {
            throw new RedisStorageException("Stored admin index part is invalid", invalid);
        }
    }

    private static JedisPool createPool(RedisStorageConfig config) {
        Objects.requireNonNull(config, "config");
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectionTimeoutMillis())
                .socketTimeoutMillis(config.connectionTimeoutMillis())
                .clientName("when-admin-query");
        if (config.password() != null) builder.password(config.password());
        return new JedisPool(new HostAndPort(config.host(), config.port()), builder.build());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public record AdminMessageQuery(
            MessageStatus status,
            SinkType sinkType,
            String businessTag,
            long from,
            long to,
            String cursor,
            int limit) {
        public AdminMessageQuery {
            if (from < 0 || to < from || to - from > MAX_RANGE.toMillis()) {
                throw new InvalidAdminTimeRangeException();
            }
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (businessTag != null && businessTag.length() > 128) {
                throw new IllegalArgumentException("businessTag is too long");
            }
            if (cursor != null && cursor.length() > 2_048) {
                throw new InvalidAdminCursorException();
            }
        }
    }

    public record AdminMessagePage(
            List<Message> items,
            String nextCursor,
            boolean hasMore,
            long indexUpdatedAt) {
        public AdminMessagePage { items = List.copyOf(items); }
    }

    public static final class InvalidAdminCursorException extends IllegalArgumentException {
        public InvalidAdminCursorException() { super("admin cursor is invalid"); }
    }

    public static final class InvalidAdminTimeRangeException extends IllegalArgumentException {
        public InvalidAdminTimeRangeException() { super("admin time range is invalid"); }
    }

    private record PendingIndex(String messageId, long deliverAt) {
        private PendingIndex {
            if (deliverAt < 0) throw new IllegalArgumentException("deliverAt must not be negative");
        }
    }

    private record Candidate(String messageId, long deliverAt) {
    }

    private record Cursor(long deliverAt, String messageId) {
        private static final Cursor START = new Cursor(Long.MIN_VALUE, "");
        private boolean before(Candidate candidate) {
            return candidate.deliverAt() > deliverAt
                    || candidate.deliverAt() == deliverAt && candidate.messageId().compareTo(messageId) > 0;
        }
    }
}
