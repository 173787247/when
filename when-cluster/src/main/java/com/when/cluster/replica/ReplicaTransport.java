package com.when.cluster.replica;

import com.when.core.ReplicaOperation;
import com.when.core.SyncAck;
import java.util.concurrent.CompletionStage;

/** Internal transport boundary, implemented by the Replica gRPC client. */
@FunctionalInterface
public interface ReplicaTransport {
    CompletionStage<SyncAck> send(ReplicaOperation operation);
}
