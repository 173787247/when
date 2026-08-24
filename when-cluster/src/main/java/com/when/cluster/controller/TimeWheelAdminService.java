package com.when.cluster.controller;

import java.util.List;

/** Controller-facing management boundary; HTTP code never mutates ETCD directly. */
public interface TimeWheelAdminService {
    List<TimeWheelAssignmentView> list();

    CreationResult create(int count, String idempotencyKey);

    record CreationResult(
            List<String> created,
            List<TimeWheelAssignmentView> assignments,
            boolean replayed) {
        public CreationResult {
            created = List.copyOf(created);
            assignments = List.copyOf(assignments);
        }
    }

    final class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException() {
            super("idempotency key was already used for another request");
        }
    }

    final class OperationConflictException extends IllegalStateException {
        public OperationConflictException() {
            super("time-wheel operation is currently in progress");
        }
    }
}
