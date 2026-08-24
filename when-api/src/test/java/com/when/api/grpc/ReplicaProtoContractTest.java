package com.when.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReplicaProtoContractTest {
    @Test
    void replicaRequestContainsOnlySchedulingReferenceFields() {
        Set<String> fields = ReplicaSyncRequest.getDescriptor().getFields().stream()
                .map(Descriptors.FieldDescriptor::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "operation_id",
                "tw_id",
                "assignment_version",
                "sequence",
                "type",
                "message_id",
                "deliver_at"), fields);
        assertFalse(fields.contains("payload"));
        assertFalse(fields.contains("sink_config"));
        assertTrue(ReplicaServiceGrpc.getSyncMethod().getFullMethodName().endsWith("/Sync"));
    }
}
