package com.when.sink.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.FileSinkConfig;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSinkTest {
    @TempDir Path baseDirectory;

    @Test
    void writesPayloadBelowConfiguredBaseDirectory() throws Exception {
        FileSink sink = new FileSink(baseDirectory);
        byte[] payload = "delayed-file-payload".getBytes(StandardCharsets.UTF_8);

        var result = sink.deliver(message("deliveries/message-1.bin", payload), "attempt-1");

        assertTrue(result.success());
        assertArrayEquals(payload, Files.readAllBytes(baseDirectory.resolve("deliveries/message-1.bin")));
    }

    @Test
    void retryWithSameTargetAtomicallyReplacesPriorPayload() throws Exception {
        FileSink sink = new FileSink(baseDirectory);
        assertTrue(sink.deliver(message("deliveries/message-1.bin", new byte[] {1}), "attempt-1").success());

        var result = sink.deliver(message("deliveries/message-1.bin", new byte[] {2, 3}), "attempt-2");

        assertTrue(result.success());
        assertArrayEquals(new byte[] {2, 3}, Files.readAllBytes(baseDirectory.resolve("deliveries/message-1.bin")));
    }

    @Test
    void rejectsAbsoluteAndEscapingPaths() {
        FileSink sink = new FileSink(baseDirectory);

        assertThrows(InvalidConfigException.class, () -> sink.validateConfig(new FileSinkConfig("../outside.bin")));
        assertThrows(InvalidConfigException.class,
                () -> sink.validateConfig(new FileSinkConfig(baseDirectory.resolve("outside.bin").toString())));
    }

    @Test
    void reportsMissingBaseDirectoryAsPermanentConfigurationFailure() {
        FileSink sink = new FileSink((String) null);

        var result = sink.deliver(message("deliveries/message-1.bin", new byte[] {1}), "attempt-1");

        assertFalse(result.success());
        assertFalse(result.retryable());
        assertEquals("FILE_CONFIG", result.errorCode());
    }

    private static Message message(String path, byte[] payload) {
        long now = System.currentTimeMillis();
        return new Message(
                "message-1", now, now, "tw-0", SinkType.FILE, new FileSinkConfig(path),
                payload, null, MessageStatus.DELIVERING, 0, now, 0, null, "trace-1");
    }
}
