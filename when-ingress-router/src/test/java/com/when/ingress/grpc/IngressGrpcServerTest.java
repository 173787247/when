package com.when.ingress.grpc;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.api.application.CancelResult;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.api.grpc.RequestValidationException;
import com.when.ingress.application.IngressValidationException;
import org.junit.jupiter.api.Test;

class IngressGrpcServerTest {
    @Test
    void mapsApplicationValidationToTheTransportValidationContract() {
        DelayMessageHandler delegate = new DelayMessageHandler() {
            @Override
            public SubmitResult submit(SubmitCommand command) {
                throw new IngressValidationException("invalid submit field");
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

        DelayMessageHandler transport = IngressGrpcServer.transportHandler(delegate);

        assertThrows(RequestValidationException.class, () -> transport.submit(null));
    }
}
