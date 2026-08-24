package com.when.api.grpc;

import com.when.api.application.DelayMessageHandler;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import com.when.observability.grpc.GrpcTraceServerInterceptor;
import com.when.observability.grpc.GrpcTraceContext;
import com.when.observability.TraceOperations;
import io.grpc.ServerInterceptors;

/** Lifecycle wrapper for the internal gRPC service and standard health service. */
public final class DelayMessageGrpcServer implements AutoCloseable {
    public static final String PORT_ENV = "WHEN_GRPC_PORT";

    private final Server server;
    private final HealthStatusManager healthStatusManager;

    public DelayMessageGrpcServer(int port, DelayMessageHandler handler) {
        this(port, handler, Clock.systemUTC(), TraceOperations.noop());
    }

    DelayMessageGrpcServer(int port, DelayMessageHandler handler, Clock clock) {
        this(port, handler, clock, TraceOperations.noop());
    }

    public DelayMessageGrpcServer(
            int port, DelayMessageHandler handler, TraceOperations traces) {
        this(port, handler, Clock.systemUTC(), traces);
    }

    DelayMessageGrpcServer(
            int port, DelayMessageHandler handler, Clock clock, TraceOperations traces) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("gRPC port must be between 0 and 65535");
        }
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(traces, "traces");
        this.healthStatusManager = new HealthStatusManager();
        this.server = NettyServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(
                        new DelayMessageServiceImpl(remoteContextHandler(handler, traces), clock),
                        new GrpcTraceServerInterceptor()))
                .addService(healthStatusManager.getHealthService())
                .build();
    }

    public static DelayMessageGrpcServer fromEnvironment(DelayMessageHandler handler) {
        return fromEnvironment(handler, System.getenv());
    }

    static DelayMessageGrpcServer fromEnvironment(
            DelayMessageHandler handler, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configuredPort = environment.get(PORT_ENV);
        if (configuredPort == null || configuredPort.isBlank()) {
            throw new IllegalStateException(PORT_ENV + " is required");
        }
        try {
            int port = Integer.parseInt(configuredPort);
            if (port < 1 || port > 65_535) {
                throw new IllegalStateException(PORT_ENV + " must be between 1 and 65535");
            }
            return new DelayMessageGrpcServer(port, handler);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(PORT_ENV + " must be an integer", exception);
        }
    }

    private static DelayMessageHandler remoteContextHandler(
            DelayMessageHandler delegate, TraceOperations traces) {
        return new DelayMessageHandler() {
            @Override
            public com.when.api.application.SubmitResult submit(
                    com.when.api.application.SubmitCommand command) {
                return remote(traces, () -> delegate.submit(command));
            }

            @Override
            public com.when.api.application.MessageView query(String messageId) {
                return remote(traces, () -> delegate.query(messageId));
            }

            @Override
            public com.when.api.application.CancelResult cancel(String messageId) {
                return remote(traces, () -> delegate.cancel(messageId));
            }
        };
    }

    private static <T> T remote(TraceOperations traces, java.util.function.Supplier<T> action) {
        return traces.withRemoteContext(GrpcTraceContext.incoming(), action);
    }

    public DelayMessageGrpcServer start() throws IOException {
        server.start();
        healthStatusManager.setStatus("", ServingStatus.SERVING);
        healthStatusManager.setStatus(
                DelayMessageServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
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
        healthStatusManager.enterTerminalState();
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
}
