package com.when.api.grpc;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.api.application.CancelResult;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelayMessageGrpcServerTest {
    private final DelayMessageHandler handler = new DelayMessageHandler() {
        @Override
        public SubmitResult submit(SubmitCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageView query(String messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CancelResult cancel(String messageId) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void requiresAValidEnvironmentPort() {
        assertThrows(IllegalStateException.class, () ->
                DelayMessageGrpcServer.fromEnvironment(handler, Map.of()));
        assertThrows(IllegalStateException.class, () ->
                DelayMessageGrpcServer.fromEnvironment(
                        handler, Map.of(DelayMessageGrpcServer.PORT_ENV, "not-a-number")));
        assertThrows(IllegalStateException.class, () ->
                DelayMessageGrpcServer.fromEnvironment(
                        handler, Map.of(DelayMessageGrpcServer.PORT_ENV, "0")));
        assertThrows(IllegalStateException.class, () ->
                DelayMessageGrpcServer.fromEnvironment(
                        handler, Map.of(DelayMessageGrpcServer.PORT_ENV, "65536")));
    }
}
