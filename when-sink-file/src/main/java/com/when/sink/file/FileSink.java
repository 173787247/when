package com.when.sink.file;

import com.when.core.DeliveryResult;
import com.when.core.FileSinkConfig;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import com.when.sink.spi.AttemptAwareSink;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Writes a delivery payload atomically below a deployment-controlled local directory. */
public final class FileSink implements AttemptAwareSink {
    public static final String BASE_DIRECTORY_ENV = "WHEN_FILE_SINK_BASE_DIR";

    private final String configuredBaseDirectory;

    /** Creates a sink whose base directory is read from {@value #BASE_DIRECTORY_ENV}. */
    public FileSink() {
        this(System.getenv(BASE_DIRECTORY_ENV));
    }

    public FileSink(Path baseDirectory) {
        this(Objects.requireNonNull(baseDirectory, "baseDirectory").toString());
    }

    FileSink(String configuredBaseDirectory) {
        this.configuredBaseDirectory = configuredBaseDirectory;
    }

    @Override
    public SinkType type() {
        return SinkType.FILE;
    }

    @Override
    public void validateConfig(SinkConfig value) throws InvalidConfigException {
        resolveTarget(requireConfig(value));
    }

    @Override
    public DeliveryResult deliver(Message message, String attemptId) {
        Objects.requireNonNull(message, "message");
        long started = System.nanoTime();
        final Path target;
        try {
            target = resolveTarget(requireConfig(message.sinkConfig()));
        } catch (InvalidConfigException exception) {
            return DeliveryResult.permanentFailure("FILE_CONFIG", "File sink configuration is invalid", elapsed(started));
        }

        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".when-file-sink-", ".tmp");
            try {
                Files.write(temporary, payload(message), StandardOpenOption.TRUNCATE_EXISTING);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return DeliveryResult.success(elapsed(started));
        } catch (IOException | SecurityException exception) {
            return DeliveryResult.retryableFailure("FILE_WRITE", "File delivery failed", elapsed(started));
        }
    }

    private Path resolveTarget(FileSinkConfig config) {
        String configuredPath = config.path();
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new InvalidConfigException("File sink path is required");
        }
        Path relative;
        try {
            relative = Path.of(configuredPath);
        } catch (RuntimeException exception) {
            throw new InvalidConfigException("File sink path is invalid", exception);
        }
        if (relative.isAbsolute()) {
            throw new InvalidConfigException("File sink path must be relative");
        }

        Path base = baseDirectory();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new InvalidConfigException("File sink path must stay below the configured base directory");
        }
        return target;
    }

    private Path baseDirectory() {
        if (configuredBaseDirectory == null || configuredBaseDirectory.isBlank()) {
            throw new InvalidConfigException("File sink base directory is not configured");
        }
        try {
            return Path.of(configuredBaseDirectory).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new InvalidConfigException("File sink base directory is invalid", exception);
        }
    }

    private static FileSinkConfig requireConfig(SinkConfig value) {
        if (!(value instanceof FileSinkConfig config)) {
            throw new InvalidConfigException("File sink requires FileSinkConfig");
        }
        return config;
    }

    private static void moveAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] payload(Message message) {
        byte[] payload = message.payload();
        return payload == null ? new byte[0] : payload;
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
