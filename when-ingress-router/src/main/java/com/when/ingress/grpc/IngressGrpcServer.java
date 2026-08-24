package com.when.ingress.grpc;

import com.when.api.application.CancelResult;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.api.grpc.DelayMessageServiceGrpc;
import com.when.api.grpc.DelayMessageServiceImpl;
import com.when.api.grpc.RequestValidationException;
import com.when.ingress.application.IngressValidationException;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Internal gRPC lifecycle that adds the identity-preserving forwarding interceptor. */
public final class IngressGrpcServer implements AutoCloseable {
    private final Server server;
    private final HealthStatusManager health = new HealthStatusManager();

    public IngressGrpcServer(int port, DelayMessageHandler handler) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("gRPC port must be between 0 and 65535");
        }
        Objects.requireNonNull(handler, "handler");
        this.server = NettyServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(
                        new DelayMessageServiceImpl(transportHandler(handler)),
                        new ForwardingServerInterceptor()))
                .addService(health.getHealthService())
                .build();
    }

    public IngressGrpcServer start() throws IOException {
        server.start();
        health.setStatus("", ServingStatus.SERVING);
        health.setStatus(DelayMessageServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
        return this;
    }

    public int port() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        health.enterTerminalState();
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
                server.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static DelayMessageHandler transportHandler(DelayMessageHandler delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new DelayMessageHandler() {
            @Override
            public SubmitResult submit(SubmitCommand command) {
                try {
                    return delegate.submit(command);
                } catch (IngressValidationException exception) {
                    throw new RequestValidationException(exception.getMessage());
                }
            }

            @Override
            public MessageView query(String messageId) {
                return delegate.query(messageId);
            }

            @Override
            public CancelResult cancel(String messageId) {
                return delegate.cancel(messageId);
            }
        };
    }
}
