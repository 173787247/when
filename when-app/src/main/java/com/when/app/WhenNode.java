package com.when.app;

import com.when.api.application.DelayMessageHandler;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import com.when.ingress.grpc.IngressGrpcServer;
import com.when.timewheel.TimeWheelRebuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle composition root for an already-configured node.
 *
 * <p>Concrete clients and plugins are supplied once by the launcher. Recovery completes before the
 * gRPC endpoint starts accepting traffic, and shutdown stops ingress before schedulers.</p>
 */
public final class WhenNode implements AutoCloseable {
    private final List<TimeWheel> timeWheels;
    private final TimeWheelRebuilder rebuilder;
    private final IngressGrpcServer grpcServer;
    private final List<AutoCloseable> ownedResources;
    private boolean started;
    private boolean closed;

    public WhenNode(
            int grpcPort,
            DelayMessageHandler handler,
            StoragePlugin storage,
            Collection<? extends TimeWheel> timeWheels,
            Collection<? extends AutoCloseable> ownedResources) {
        this.timeWheels = List.copyOf(Objects.requireNonNull(timeWheels, "timeWheels"));
        if (this.timeWheels.isEmpty()) {
            throw new IllegalArgumentException("at least one local time wheel is required");
        }
        this.rebuilder = new TimeWheelRebuilder(Objects.requireNonNull(storage, "storage"));
        this.grpcServer = new IngressGrpcServer(grpcPort, Objects.requireNonNull(handler, "handler"));
        this.ownedResources = List.copyOf(Objects.requireNonNull(ownedResources, "ownedResources"));
    }

    public synchronized WhenNode start() throws IOException {
        if (closed) {
            throw new IllegalStateException("node is closed");
        }
        if (started) {
            return this;
        }
        List<TimeWheel> recovered = new ArrayList<>();
        try {
            for (TimeWheel wheel : timeWheels) {
                rebuilder.rebuildAndStart(wheel);
                recovered.add(wheel);
            }
            grpcServer.start();
            started = true;
            return this;
        } catch (IOException | RuntimeException exception) {
            Collections.reverse(recovered);
            recovered.forEach(TimeWheel::stop);
            throw exception;
        }
    }

    public synchronized int grpcPort() {
        if (!started) {
            throw new IllegalStateException("node is not started");
        }
        return grpcServer.port();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        grpcServer.close();
        List<TimeWheel> reversedWheels = new ArrayList<>(timeWheels);
        Collections.reverse(reversedWheels);
        reversedWheels.forEach(TimeWheel::stop);
        List<AutoCloseable> reversedResources = new ArrayList<>(ownedResources);
        Collections.reverse(reversedResources);
        RuntimeException failure = null;
        for (AutoCloseable resource : reversedResources) {
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("failed to close node resource", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
