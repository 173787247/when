package com.when.cluster.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.EtcdSnapshot;
import com.when.cluster.etcd.EtcdTxnResult;
import com.when.cluster.etcd.EtcdValue;
import com.when.cluster.etcd.EtcdWatchEvent;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import com.when.cluster.membership.ClusterMetadataCodec;
import com.when.cluster.membership.NodeInfo;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Assignment authority using term + mod-revision + old-value etcd transactions. */
public final class EtcdAssignmentStore implements AssignmentStore {
    private final String controllerNodeId;
    private final EtcdMetadataClient client;
    private final MetadataJsonCodec metadataCodec;
    private final ClusterMetadataCodec clusterCodec;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EtcdAssignmentStore(String controllerNodeId, EtcdMetadataClient client) {
        this(controllerNodeId, client, Clock.systemUTC());
    }

    public EtcdAssignmentStore(
            String controllerNodeId,
            EtcdMetadataClient client,
            Clock clock) {
        this.controllerNodeId = Text.segment(controllerNodeId, "controllerNodeId");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadataCodec = new MetadataJsonCodec();
        this.clusterCodec = new ClusterMetadataCodec();
        this.mapper = new ObjectMapper();
    }

    @Override
    public ClusterState loadSnapshot(long observedAt) {
        if (observedAt < 0) {
            throw new IllegalArgumentException("observedAt must not be negative");
        }
        EtcdSnapshot snapshot = client.getPrefixSnapshot(EtcdKeys.ROOT_PREFIX);
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        Map<String, AssignmentRecord> assignments = new LinkedHashMap<>();
        snapshot.values().forEach((key, value) -> {
            if (key.startsWith(EtcdKeys.NODES_PREFIX)) {
                String nodeId = segment(key, EtcdKeys.NODES_PREFIX);
                NodeInfo node = clusterCodec.decodeNode(nodeId, value.value());
                long eligibleSince = Math.min(node.startTime(), observedAt);
                nodes.put(nodeId, new NodeState(nodeId, true, false, eligibleSince));
            } else if (key.startsWith(EtcdKeys.TIME_WHEELS_PREFIX)) {
                String twId = segment(key, EtcdKeys.TIME_WHEELS_PREFIX);
                TimeWheelMetadata metadata = metadataCodec.decodeTimeWheel(value.value());
                assignments.put(twId, new AssignmentRecord(
                        twId, metadata, value.modRevision(), metadata.lastMovedAt()));
            }
        });
        return new ClusterState(snapshot.revision(), observedAt, nodes, assignments);
    }

    @Override
    public CommitResult compareAndSet(AssignmentDecision decision) {
        Objects.requireNonNull(decision, "decision");
        String key = EtcdKeys.timeWheel(decision.twId());
        Optional<EtcdValue> encodedCurrent = client.getValue(key);
        Optional<AssignmentRecord> current = encodedCurrent.map(value -> record(decision.twId(), value));

        if (current.map(value -> decision.decisionId().equals(value.metadata().decisionId()))
                .orElse(false)) {
            return new CommitResult(true, current.orElseThrow().modRevision(), current);
        }
        if (encodedCurrent.map(EtcdValue::modRevision).orElse(0L)
                != decision.expectedModRevision()) {
            return new CommitResult(false, current.map(AssignmentRecord::modRevision).orElse(0L), current);
        }

        TimeWheelMetadata replacement;
        try {
            replacement = apply(decision, current.map(AssignmentRecord::metadata).orElse(null));
        } catch (IllegalStateException rejected) {
            return new CommitResult(false, current.map(AssignmentRecord::modRevision).orElse(0L), current);
        }
        String replacementValue = metadataCodec.encodeTimeWheel(replacement);
        String progressValue = encodeProgress(new ControllerProgress(
                decision.controllerTerm(),
                Math.max(decision.expectedModRevision(), current.map(AssignmentRecord::modRevision).orElse(0L)),
                decision.decisionId()));
        EtcdTxnResult transaction = client.txnControllerAssignment(
                controllerNodeId,
                decision.controllerTerm(),
                key,
                decision.expectedModRevision(),
                encodedCurrent.map(EtcdValue::value).orElse(null),
                replacementValue,
                progressValue);
        if (!transaction.committed()) {
            Optional<AssignmentRecord> refreshed = client.getValue(key)
                    .map(value -> record(decision.twId(), value));
            return new CommitResult(false, transaction.revision(), refreshed);
        }
        AssignmentRecord committed = new AssignmentRecord(
                decision.twId(), replacement, transaction.revision(), replacement.lastMovedAt());
        return new CommitResult(true, transaction.revision(), Optional.of(committed));
    }

    @Override
    public boolean recordProgress(ControllerTerm term, ClusterEvent event) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(event, "event");
        if (!controllerNodeId.equals(term.controllerNodeId())) {
            return false;
        }
        return client.txnControllerProgress(
                controllerNodeId,
                term.term(),
                encodeProgress(new ControllerProgress(
                        term.term(), event.revision(), event.eventId())));
    }

    @Override
    public WatchHandle watchFrom(
            long revision,
            Consumer<ClusterEvent> eventHandler,
            Runnable snapshotRequired) {
        if (revision <= 0) {
            throw new IllegalArgumentException("watch revision must be positive");
        }
        Objects.requireNonNull(eventHandler, "eventHandler");
        Objects.requireNonNull(snapshotRequired, "snapshotRequired");
        EtcdMetadataClient.WatchHandle watch = client.watchPrefix(
                EtcdKeys.ROOT_PREFIX,
                revision,
                event -> mapEvent(event).ifPresent(eventHandler),
                ignored -> snapshotRequired.run());
        return watch::close;
    }

    private Optional<ClusterEvent> mapEvent(EtcdWatchEvent event) {
        if (event.key().startsWith(EtcdKeys.NODES_PREFIX)) {
            String nodeId = segment(event.key(), EtcdKeys.NODES_PREFIX);
            ControllerEventType type = event.type() == EtcdWatchEvent.Type.DELETE
                    ? ControllerEventType.NODE_LEFT
                    : ControllerEventType.NODE_JOINED;
            return Optional.of(new ClusterEvent(
                    eventId(type, event.key(), event.revision()),
                    event.revision(),
                    type,
                    nodeId,
                    null));
        }
        if (event.key().startsWith(EtcdKeys.TIME_WHEELS_PREFIX)) {
            String twId = segment(event.key(), EtcdKeys.TIME_WHEELS_PREFIX);
            ControllerEventType type = ControllerEventType.TIME_WHEEL_CHANGED;
            if (event.type() == EtcdWatchEvent.Type.PUT
                    && "out_of_sync".equals(metadataCodec.decodeTimeWheel(event.value()).syncState())) {
                type = ControllerEventType.SLAVE_OUT_OF_SYNC;
            }
            return Optional.of(new ClusterEvent(
                    eventId(type, event.key(), event.revision()),
                    event.revision(),
                    type,
                    null,
                    twId));
        }
        return Optional.empty();
    }

    private TimeWheelMetadata apply(
            AssignmentDecision decision,
            TimeWheelMetadata current) {
        long now = clock.millis();
        if (decision.action() == AssignmentAction.CREATE_TIME_WHEEL) {
            if (current != null || decision.expectedModRevision() != 0
                    || decision.nextAssignmentVersion() != 1) {
                throw new IllegalStateException("time wheel already exists or creation version is invalid");
            }
            return new TimeWheelMetadata(
                    decision.fromNode(),
                    decision.toNode(),
                    "running",
                    "in_sync",
                    1,
                    decision.decisionId(),
                    null,
                    null,
                    now);
        }
        if (current == null
                || decision.nextAssignmentVersion() != current.assignmentVersion() + 1) {
            throw new IllegalStateException("assignment version is stale");
        }
        return switch (decision.action()) {
            case PROMOTE_SLAVE -> promote(decision, current, now);
            case RECOVER_MASTER -> recoverMaster(decision, current, now);
            case ASSIGN_CANDIDATE -> assignCandidate(decision, current, now);
            case ABORT_CANDIDATE -> abortCandidate(decision, current);
            case MARK_CANDIDATE_IN_SYNC -> markCandidateInSync(decision, current);
            case COMPLETE_SLAVE_MOVE -> completeMove(decision, current, now);
            case REBUILD_SLAVE -> rebuildSlave(decision, current);
            case CREATE_TIME_WHEEL -> throw new IllegalStateException("unreachable");
        };
    }

    private static TimeWheelMetadata promote(
            AssignmentDecision decision,
            TimeWheelMetadata current,
            long now) {
        if (!Objects.equals(decision.fromNode(), current.master())
                || !Objects.equals(decision.toNode(), current.slave())
                || !"in_sync".equals(current.syncState())) {
            throw new IllegalStateException("only the current in-sync Slave can be promoted");
        }
        return new TimeWheelMetadata(
                current.slave(),
                current.master(),
                "recovering",
                "out_of_sync",
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                null,
                null,
                now);
    }

    private static TimeWheelMetadata assignCandidate(
            AssignmentDecision decision,
            TimeWheelMetadata current,
            long now) {
        if (!Objects.equals(decision.fromNode(), current.slave())
                || current.candidateSlave() != null
                || decision.toNode().equals(current.master())
                || decision.toNode().equals(current.slave())) {
            throw new IllegalStateException("candidate movement violates replica placement");
        }
        return new TimeWheelMetadata(
                current.master(),
                current.slave(),
                current.status(),
                current.syncState(),
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                decision.toNode(),
                "rebuilding",
                now);
    }

    private static TimeWheelMetadata recoverMaster(
            AssignmentDecision decision,
            TimeWheelMetadata current,
            long now) {
        if (!Objects.equals(decision.fromNode(), current.master())
                || decision.toNode().equals(current.master())) {
            throw new IllegalStateException("recovery must replace the failed Master");
        }
        String retainedSlave = current.slave().equals(decision.toNode())
                ? current.master()
                : current.slave();
        return new TimeWheelMetadata(
                decision.toNode(),
                retainedSlave,
                "recovering",
                "out_of_sync",
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                null,
                null,
                now);
    }

    private static TimeWheelMetadata abortCandidate(
            AssignmentDecision decision,
            TimeWheelMetadata current) {
        if (!Objects.equals(decision.toNode(), current.candidateSlave())) {
            throw new IllegalStateException("only the current candidate can be aborted");
        }
        return new TimeWheelMetadata(
                current.master(),
                current.slave(),
                current.status(),
                current.syncState(),
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                null,
                null,
                current.lastMovedAt());
    }

    private static TimeWheelMetadata markCandidateInSync(
            AssignmentDecision decision,
            TimeWheelMetadata current) {
        if (!Objects.equals(decision.toNode(), current.candidateSlave())
                || !"rebuilding".equals(current.candidateState())) {
            throw new IllegalStateException("candidate is not rebuilding");
        }
        return new TimeWheelMetadata(
                current.master(),
                current.slave(),
                current.status(),
                current.syncState(),
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                current.candidateSlave(),
                "in_sync",
                current.lastMovedAt());
    }

    private static TimeWheelMetadata completeMove(
            AssignmentDecision decision,
            TimeWheelMetadata current,
            long now) {
        if (!Objects.equals(decision.fromNode(), current.slave())
                || !Objects.equals(decision.toNode(), current.candidateSlave())
                || !"in_sync".equals(current.candidateState())) {
            throw new IllegalStateException("only an in-sync candidate can replace the old Slave");
        }
        return new TimeWheelMetadata(
                current.master(),
                current.candidateSlave(),
                "running",
                "in_sync",
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                null,
                null,
                now);
    }

    private static TimeWheelMetadata rebuildSlave(
            AssignmentDecision decision,
            TimeWheelMetadata current) {
        if (!Objects.equals(decision.toNode(), current.slave())) {
            throw new IllegalStateException("only the assigned Slave can be rebuilt");
        }
        return new TimeWheelMetadata(
                current.master(),
                current.slave(),
                current.status(),
                "rebuilding",
                decision.nextAssignmentVersion(),
                decision.decisionId(),
                current.candidateSlave(),
                current.candidateState(),
                current.lastMovedAt());
    }

    private AssignmentRecord record(String twId, EtcdValue value) {
        TimeWheelMetadata metadata = metadataCodec.decodeTimeWheel(value.value());
        return new AssignmentRecord(twId, metadata, value.modRevision(), metadata.lastMovedAt());
    }

    private String encodeProgress(ControllerProgress progress) {
        try {
            return mapper.writeValueAsString(progress);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Controller progress cannot be encoded", exception);
        }
    }

    private static String segment(String key, String prefix) {
        String value = key.substring(prefix.length());
        return Text.segment(value, "metadata key segment");
    }

    private static String eventId(ControllerEventType type, String key, long revision) {
        return type.name().toLowerCase() + ":" + key + ":" + revision;
    }
}
