package com.when.cluster.controller;

import com.when.cluster.etcd.TimeWheelMetadata;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-threaded Controller event loop. Decisions are recalculated after CAS conflicts and no
 * executor is called until the fenced metadata transaction has committed.
 */
public final class DefaultController implements Controller, AutoCloseable {
    public static final Duration DEFAULT_EVENT_COALESCE = Duration.ofMillis(500);
    public static final Duration DEFAULT_LOAD_CHECK_INTERVAL = Duration.ofMinutes(5);

    private static final Logger LOGGER = Logger.getLogger(DefaultController.class.getName());
    private static final int MAX_CAS_RECALCULATIONS = 4;
    private static final int MAX_REMEMBERED_EVENTS = 2_048;

    private final AssignmentStore assignments;
    private final RebalancePlanner planner;
    private final RebalancePolicy policy;
    private final ControllerActionExecutor actionExecutor;
    private final Clock clock;
    private final Duration eventCoalesce;
    private final Duration loadCheckInterval;
    private final ScheduledExecutorService eventLoop;
    private final Object stateLock = new Object();
    private final Map<String, ClusterEvent> pendingEvents = new HashMap<>();
    private final LinkedHashSet<String> processedEventIds = new LinkedHashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ControllerTerm term;
    private ClusterState state;
    private AssignmentStore.WatchHandle watch;
    private ScheduledFuture<?> drainTask;
    private ScheduledFuture<?> periodicTask;

    public DefaultController(
            AssignmentStore assignments,
            RebalancePlanner planner,
            RebalancePolicy policy,
            ControllerActionExecutor actionExecutor) {
        this(
                assignments,
                planner,
                policy,
                actionExecutor,
                Clock.systemUTC(),
                DEFAULT_EVENT_COALESCE,
                DEFAULT_LOAD_CHECK_INTERVAL);
    }

    public DefaultController(
            AssignmentStore assignments,
            RebalancePlanner planner,
            RebalancePolicy policy,
            ControllerActionExecutor actionExecutor,
            Clock clock,
            Duration eventCoalesce,
            Duration loadCheckInterval) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventCoalesce = nonNegative(eventCoalesce, "eventCoalesce");
        this.loadCheckInterval = positive(loadCheckInterval, "loadCheckInterval");
        this.eventLoop = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-controller-decisions");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onControllerElected(ControllerTerm newTerm) {
        Objects.requireNonNull(newTerm, "term");
        synchronized (stateLock) {
            requireOpen();
            term = newTerm;
            reloadSnapshotAndWatch();
            if (periodicTask != null) {
                periodicTask.cancel(false);
            }
            periodicTask = eventLoop.scheduleWithFixedDelay(
                    this::enqueuePeriodicCheck,
                    loadCheckInterval.toMillis(),
                    loadCheckInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            // Watch starts at revision+1; NODE_LEFT that already happened (e.g. previous
            // Controller died with the Master) must be reconciled from membership.
            // Lease expiry can lag election, so retry briefly after winning.
            eventLoop.execute(this::reconcileAbsentAssignedNodes);
            for (long delaySeconds : new long[] {2L, 5L, 10L, 15L, 20L}) {
                eventLoop.schedule(
                        this::reconcileAbsentAssignedNodes,
                        delaySeconds,
                        TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void onClusterEvent(ClusterEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (stateLock) {
            requireReady();
            if (processedEventIds.contains(event.eventId())) {
                return;
            }
            String key = coalesceKey(event);
            pendingEvents.merge(key, event, (left, right) ->
                    left.revision() >= right.revision() ? left : right);
            if (drainTask == null || drainTask.isDone()) {
                drainTask = eventLoop.schedule(
                        this::drainEvents,
                        eventCoalesce.toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public TimeWheelAssignmentView createTimeWheel(TimeWheelSpec spec) {
        Objects.requireNonNull(spec, "spec");
        synchronized (stateLock) {
            requireReady();
            for (int attempt = 0; attempt < MAX_CAS_RECALCULATIONS; attempt++) {
                state = assignments.loadSnapshot(clock.millis());
                AssignmentRecord existing = state.assignments().get(spec.twId());
                if (existing != null) {
                    return view(existing);
                }
                List<String> targets = leastLoadedEligibleNodes(state, Set.of(), 2);
                if (targets.size() < 2) {
                    throw new IllegalStateException(
                            "at least two stable ready nodes are required to create a time wheel");
                }
                AssignmentDecision decision = new AssignmentDecision(
                        "create:" + spec.twId(),
                        term.term(),
                        spec.twId(),
                        0,
                        1,
                        AssignmentAction.CREATE_TIME_WHEEL,
                        targets.get(0),
                        targets.get(1));
                CommitResult result = assignments.compareAndSet(decision);
                if (result.committed()) {
                    AssignmentRecord committed = result.assignment().orElseThrow();
                    state = assignments.loadSnapshot(clock.millis());
                    executeAfterCommit(decision, committed);
                    return view(committed);
                }
            }
            throw new IllegalStateException("time-wheel creation conflicted repeatedly");
        }
    }

    public ClusterState currentState() {
        synchronized (stateLock) {
            requireReady();
            return state;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (stateLock) {
            closeWatch();
            if (drainTask != null) {
                drainTask.cancel(false);
            }
            if (periodicTask != null) {
                periodicTask.cancel(false);
            }
            pendingEvents.clear();
        }
        eventLoop.shutdownNow();
    }

    private void drainEvents() {
        List<ClusterEvent> events;
        synchronized (stateLock) {
            if (closed.get() || term == null) {
                return;
            }
            events = pendingEvents.values().stream()
                    .sorted(Comparator.comparingLong(ClusterEvent::revision)
                            .thenComparing(ClusterEvent::eventId))
                    .toList();
            pendingEvents.clear();
        }
        for (ClusterEvent event : events) {
            synchronized (stateLock) {
                if (processedEventIds.contains(event.eventId())) {
                    continue;
                }
                try {
                    process(event);
                    if (!assignments.recordProgress(term, event)) {
                        throw new IllegalStateException("Controller term changed while recording progress");
                    }
                    rememberProcessed(event.eventId());
                } catch (RuntimeException exception) {
                    LOGGER.log(
                            Level.WARNING,
                            "operation=controller_decision status=failed event_type={0} error_type={1}",
                            new Object[] {event.type(), exception.getClass().getSimpleName()});
                }
            }
        }
    }

    private void process(ClusterEvent event) {
        state = assignments.loadSnapshot(clock.millis());
        switch (event.type()) {
            case NODE_LEFT -> handleNodeLeft(event.nodeId());
            case SLAVE_OUT_OF_SYNC -> handleOutOfSync(event.twId());
            case NODE_JOINED -> handleNodeJoined(event);
            case PERIODIC_REBALANCE -> {
                reconcileAbsentAssignedNodesLocked();
                applyRebalance();
            }
            case TIME_WHEEL_CHANGED -> completeReadyCandidate(event.twId());
        }
        state = assignments.loadSnapshot(clock.millis());
    }

    /**
     * Promote / replace replicas whose Master or Slave is no longer a healthy member.
     * Covers leadership-change watch gaps where etcd DELETE events were already observed
     * only by the previous Controller.
     */
    private void reconcileAbsentAssignedNodes() {
        synchronized (stateLock) {
            if (closed.get() || term == null || state == null) {
                return;
            }
            reconcileAbsentAssignedNodesLocked();
        }
    }

    private void reconcileAbsentAssignedNodesLocked() {
        state = assignments.loadSnapshot(clock.millis());
        TreeSet<String> missing = new TreeSet<>();
        for (AssignmentRecord assignment : state.assignments().values()) {
            TimeWheelMetadata metadata = assignment.metadata();
            if (!healthyNode(state, metadata.master())) {
                missing.add(metadata.master());
            }
            if (!healthyNode(state, metadata.slave())) {
                missing.add(metadata.slave());
            }
            if (metadata.candidateSlave() != null
                    && !healthyNode(state, metadata.candidateSlave())) {
                missing.add(metadata.candidateSlave());
            }
        }
        for (String nodeId : missing) {
            handleNodeLeft(nodeId);
        }
    }

    private void handleNodeLeft(String failedNode) {
        List<String> affected = state.assignments().values().stream()
                .filter(assignment -> assignment.metadata().master().equals(failedNode))
                .map(AssignmentRecord::twId)
                .sorted()
                .toList();
        for (String twId : affected) {
            for (int attempt = 0; attempt < MAX_CAS_RECALCULATIONS; attempt++) {
                state = assignments.loadSnapshot(clock.millis());
                AssignmentRecord current = state.assignments().get(twId);
                if (current == null || !current.metadata().master().equals(failedNode)) {
                    break;
                }
                TimeWheelMetadata metadata = current.metadata();
                boolean directPromotion = "in_sync".equals(metadata.syncState())
                        && healthyNode(state, metadata.slave());
                String newMaster = directPromotion
                        ? metadata.slave()
                        : leastLoadedEligibleNodes(state, Set.of(failedNode), 1).stream()
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "no healthy node is available for Redis recovery"));
                AssignmentAction action = directPromotion
                        ? AssignmentAction.PROMOTE_SLAVE
                        : AssignmentAction.RECOVER_MASTER;
                AssignmentDecision decision = decision(
                        "failover:" + twId + ":" + metadata.assignmentVersion(),
                        current,
                        action,
                        failedNode,
                        newMaster);
                CommitResult committed = assignments.compareAndSet(decision);
                if (!committed.committed()) {
                    continue;
                }
                AssignmentRecord promoted = committed.assignment().orElseThrow();
                executeAfterCommit(decision, promoted);
                ensureReplacementSlave(promoted, failedNode);
                break;
            }
        }
        replaceFailedReplicaTargets(failedNode);
    }

    private void replaceFailedReplicaTargets(String failedNode) {
        state = assignments.loadSnapshot(clock.millis());
        List<String> failedCandidates = state.assignments().values().stream()
                .filter(assignment -> failedNode.equals(assignment.metadata().candidateSlave()))
                .map(AssignmentRecord::twId)
                .sorted()
                .toList();
        for (String twId : failedCandidates) {
            state = assignments.loadSnapshot(clock.millis());
            AssignmentRecord current = state.assignments().get(twId);
            if (current != null && failedNode.equals(current.metadata().candidateSlave())) {
                submitCurrent(
                        current,
                        AssignmentAction.ABORT_CANDIDATE,
                        current.metadata().slave(),
                        failedNode,
                        "abort-candidate:" + twId + ":"
                                + current.metadata().assignmentVersion());
            }
        }

        state = assignments.loadSnapshot(clock.millis());
        List<String> failedSlaves = state.assignments().values().stream()
                .filter(assignment -> failedNode.equals(assignment.metadata().slave()))
                .filter(assignment -> assignment.metadata().candidateSlave() == null)
                .map(AssignmentRecord::twId)
                .sorted()
                .toList();
        for (String twId : failedSlaves) {
            state = assignments.loadSnapshot(clock.millis());
            AssignmentRecord current = state.assignments().get(twId);
            if (current == null
                    || !failedNode.equals(current.metadata().slave())
                    || current.metadata().candidateSlave() != null) {
                continue;
            }
            Set<String> excluded = Set.of(current.metadata().master(), failedNode);
            List<String> targets = leastLoadedEligibleNodes(state, excluded, 1);
            if (!targets.isEmpty()) {
                submitCurrent(
                        current,
                        AssignmentAction.ASSIGN_CANDIDATE,
                        failedNode,
                        targets.get(0),
                        "replace-slave:" + twId + ":"
                                + current.metadata().assignmentVersion());
            }
        }
    }

    private void handleNodeJoined(ClusterEvent event) {
        applyRebalance();
        NodeState joined = state.nodes().get(event.nodeId());
        if (joined == null || joined.canReceiveReplica(clock.millis(), policy)) {
            return;
        }
        long elapsed = Math.max(0, clock.millis() - joined.eligibleSince());
        long delay = Math.max(1, policy.nodeStablePeriod().toMillis() - elapsed);
        eventLoop.schedule(() -> onClusterEvent(new ClusterEvent(
                        "stable:" + event.nodeId() + ":" + event.revision(),
                        event.revision(),
                        ControllerEventType.PERIODIC_REBALANCE,
                        null,
                        null)),
                delay,
                TimeUnit.MILLISECONDS);
    }

    private void ensureReplacementSlave(AssignmentRecord promoted, String failedNode) {
        state = assignments.loadSnapshot(clock.millis());
        AssignmentRecord current = state.assignments().get(promoted.twId());
        if (current == null) {
            return;
        }
        TimeWheelMetadata metadata = current.metadata();
        if (healthyNode(state, metadata.slave()) && !metadata.slave().equals(failedNode)) {
            if (!"in_sync".equals(metadata.syncState())) {
                submitCurrent(
                        current,
                        AssignmentAction.REBUILD_SLAVE,
                        metadata.slave(),
                        metadata.slave(),
                        "rebuild:" + current.twId() + ":" + metadata.assignmentVersion());
            }
            return;
        }
        Set<String> excluded = new LinkedHashSet<>();
        excluded.add(metadata.master());
        excluded.add(metadata.slave());
        excluded.add(failedNode);
        List<String> targets = leastLoadedEligibleNodes(state, excluded, 1);
        if (targets.isEmpty()) {
            return;
        }
        submitCurrent(
                current,
                AssignmentAction.ASSIGN_CANDIDATE,
                metadata.slave(),
                targets.get(0),
                "replacement:" + current.twId() + ":" + metadata.assignmentVersion());
    }

    private void handleOutOfSync(String twId) {
        AssignmentRecord current = state.assignments().get(twId);
        if (current == null || !"out_of_sync".equals(current.metadata().syncState())) {
            return;
        }
        submitCurrent(
                current,
                AssignmentAction.REBUILD_SLAVE,
                current.metadata().slave(),
                current.metadata().slave(),
                "rebuild:" + twId + ":" + current.metadata().assignmentVersion());
    }

    private void applyRebalance() {
        for (MoveAction movement : planner.plan(state.withObservedAt(clock.millis()), policy)) {
            state = assignments.loadSnapshot(clock.millis());
            AssignmentRecord current = state.assignments().get(movement.twId());
            if (current == null
                    || movement.role() != ReplicaRole.SLAVE
                    || current.metadata().assignmentVersion()
                            != movement.expectedAssignmentVersion()) {
                continue;
            }
            submitCurrent(
                    current,
                    AssignmentAction.ASSIGN_CANDIDATE,
                    movement.fromNode(),
                    movement.toNode(),
                    "rebalance:" + movement.twId() + ":"
                            + current.metadata().assignmentVersion() + ":" + movement.toNode());
        }
    }

    private void completeReadyCandidate(String twId) {
        if (twId == null) {
            return;
        }
        AssignmentRecord current = state.assignments().get(twId);
        if (current == null || !"in_sync".equals(current.metadata().candidateState())) {
            return;
        }
        submitCurrent(
                current,
                AssignmentAction.COMPLETE_SLAVE_MOVE,
                current.metadata().slave(),
                current.metadata().candidateSlave(),
                "complete:" + twId + ":" + current.metadata().assignmentVersion());
    }

    private CommitResult submitCurrent(
            AssignmentRecord current,
            AssignmentAction action,
            String fromNode,
            String toNode,
            String decisionId) {
        AssignmentDecision decision = decision(
                decisionId, current, action, fromNode, toNode);
        CommitResult result = assignments.compareAndSet(decision);
        if (result.committed()) {
            executeAfterCommit(decision, result.assignment().orElseThrow());
        }
        return result;
    }

    private AssignmentDecision decision(
            String decisionId,
            AssignmentRecord current,
            AssignmentAction action,
            String fromNode,
            String toNode) {
        return new AssignmentDecision(
                decisionId,
                term.term(),
                current.twId(),
                current.modRevision(),
                current.metadata().assignmentVersion() + 1,
                action,
                fromNode,
                toNode);
    }

    private void executeAfterCommit(
            AssignmentDecision decision,
            AssignmentRecord committed) {
        actionExecutor.execute(decision, committed).whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable root = failure;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                LOGGER.log(
                        Level.WARNING,
                        "operation=controller_action status=failed action={0} error_type={1} detail={2}",
                        new Object[] {
                            decision.action(),
                            root.getClass().getSimpleName(),
                            root.getMessage()
                        });
            } else if (decision.action() == AssignmentAction.ASSIGN_CANDIDATE) {
                eventLoop.execute(() -> candidateRebuildCompleted(decision, committed));
            }
        });
    }

    private void candidateRebuildCompleted(
            AssignmentDecision rebuildDecision,
            AssignmentRecord rebuilding) {
        synchronized (stateLock) {
            if (closed.get() || term == null) {
                return;
            }
            state = assignments.loadSnapshot(clock.millis());
            AssignmentRecord current = state.assignments().get(rebuilding.twId());
            if (current == null
                    || !Objects.equals(
                            current.metadata().candidateSlave(), rebuildDecision.toNode())
                    || !"rebuilding".equals(current.metadata().candidateState())) {
                return;
            }
            CommitResult marked = submitCurrent(
                    current,
                    AssignmentAction.MARK_CANDIDATE_IN_SYNC,
                    current.metadata().slave(),
                    current.metadata().candidateSlave(),
                    "candidate-ready:" + current.twId() + ":"
                            + current.metadata().assignmentVersion());
            if (!marked.committed()) {
                return;
            }
            AssignmentRecord ready = marked.assignment().orElseThrow();
            submitCurrent(
                    ready,
                    AssignmentAction.COMPLETE_SLAVE_MOVE,
                    ready.metadata().slave(),
                    ready.metadata().candidateSlave(),
                    "complete:" + ready.twId() + ":" + ready.metadata().assignmentVersion());
        }
    }

    private void reloadSnapshotAndWatch() {
        closeWatch();
        state = assignments.loadSnapshot(clock.millis());
        watch = assignments.watchFrom(
                state.revision() + 1,
                this::onClusterEvent,
                () -> eventLoop.execute(this::recoverCompactedWatch));
    }

    private void recoverCompactedWatch() {
        synchronized (stateLock) {
            if (closed.get() || term == null) {
                return;
            }
            reloadSnapshotAndWatch();
            onClusterEvent(new ClusterEvent(
                    "watch-recovery:" + state.revision(),
                    state.revision(),
                    ControllerEventType.PERIODIC_REBALANCE,
                    null,
                    null));
        }
    }

    private void enqueuePeriodicCheck() {
        synchronized (stateLock) {
            if (closed.get() || term == null) {
                return;
            }
            long now = clock.millis();
            onClusterEvent(new ClusterEvent(
                    "periodic:" + now,
                    state == null ? 0 : state.revision(),
                    ControllerEventType.PERIODIC_REBALANCE,
                    null,
                    null));
        }
    }

    private List<String> leastLoadedEligibleNodes(
            ClusterState snapshot,
            Set<String> excluded,
            int limit) {
        Map<String, Integer> load = new LinkedHashMap<>();
        snapshot.nodes().values().stream()
                .filter(node -> node.canReceiveReplica(snapshot.observedAt(), policy))
                .filter(node -> !excluded.contains(node.nodeId()))
                .sorted(Comparator.comparing(NodeState::nodeId))
                .forEach(node -> load.put(node.nodeId(), 0));
        snapshot.assignments().values().forEach(assignment -> {
            TimeWheelMetadata metadata = assignment.metadata();
            increment(load, metadata.master());
            increment(load, metadata.slave());
            increment(load, metadata.candidateSlave());
        });
        return load.keySet().stream()
                .sorted(Comparator.comparingInt((String node) -> load.get(node))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(limit)
                .toList();
    }

    private static void increment(Map<String, Integer> load, String nodeId) {
        if (nodeId != null && load.containsKey(nodeId)) {
            load.compute(nodeId, (ignored, count) -> count + 1);
        }
    }

    private static boolean healthyNode(ClusterState state, String nodeId) {
        NodeState node = state.nodes().get(nodeId);
        return node != null && node.ready() && !node.draining();
    }

    private static String coalesceKey(ClusterEvent event) {
        if (event.nodeId() != null) {
            return "node:" + event.nodeId();
        }
        if (event.twId() != null) {
            return "wheel:" + event.twId();
        }
        return event.type().name();
    }

    private void rememberProcessed(String eventId) {
        processedEventIds.add(eventId);
        while (processedEventIds.size() > MAX_REMEMBERED_EVENTS) {
            var iterator = processedEventIds.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static TimeWheelAssignmentView view(AssignmentRecord assignment) {
        TimeWheelMetadata metadata = assignment.metadata();
        return new TimeWheelAssignmentView(
                assignment.twId(),
                metadata.master(),
                metadata.slave(),
                metadata.status(),
                metadata.syncState(),
                metadata.assignmentVersion());
    }

    private void requireReady() {
        requireOpen();
        if (term == null || state == null || watch == null) {
            throw new IllegalStateException("Controller has not loaded an elected term");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Controller is closed");
        }
    }

    private void closeWatch() {
        if (watch != null) {
            watch.close();
            watch = null;
        }
    }

    private static Duration nonNegative(Duration duration, String field) {
        if (duration == null) {
            throw new NullPointerException(field);
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return duration;
    }

    private static Duration positive(Duration duration, String field) {
        duration = nonNegative(duration, field);
        if (duration.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }
}
