package com.when.cluster.replica;

import com.when.core.TimeWheelAssignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FencedDueMessageHandlerTest {
    @Test
    void checksNodeAndAssignmentVersionBeforeEveryDueHandoff() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "master", "slave", "running", "in_sync", 7));
        AtomicInteger delivered = new AtomicInteger();
        FencedDueMessageHandler handler = new FencedDueMessageHandler(
                "master", "tw-a", 7, assignments, ignored -> delivered.incrementAndGet());

        handler.onDue("one");
        assignments.put(new TimeWheelAssignment(
                "tw-a", "slave", "master", "switching", "in_sync", 8));

        assertThrows(IllegalStateException.class, () -> handler.onDue("two"));
        assertEquals(1, delivered.get());
    }
}
