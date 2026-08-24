package com.when.testsupport;

import com.when.core.DeliveryResult;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.Sink;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic successful Sink used only by the first integration run. */
public final class RecordingTestSink implements Sink {
    private final SinkType type;
    private final CopyOnWriteArrayList<String> deliveredMessageIds = new CopyOnWriteArrayList<>();

    public RecordingTestSink(SinkType type) {
        this.type = java.util.Objects.requireNonNull(type, "type");
    }

    @Override
    public SinkType type() {
        return type;
    }

    @Override
    public void validateConfig(SinkConfig config) throws InvalidConfigException {
        if (config == null || config.type() != type) {
            throw new InvalidConfigException("sink configuration type does not match test Sink");
        }
    }

    @Override
    public DeliveryResult deliver(Message message) {
        deliveredMessageIds.add(message.messageId());
        return new DeliveryResult(true, false, null, 0);
    }

    public List<String> deliveredMessageIds() {
        return List.copyOf(deliveredMessageIds);
    }
}
