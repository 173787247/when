package com.when.cluster.controller;

public enum AssignmentAction {
    CREATE_TIME_WHEEL,
    PROMOTE_SLAVE,
    RECOVER_MASTER,
    ASSIGN_CANDIDATE,
    ABORT_CANDIDATE,
    MARK_CANDIDATE_IN_SYNC,
    COMPLETE_SLAVE_MOVE,
    REBUILD_SLAVE
}
