package com.when.cluster.membership;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Node lifecycle, election and read-only membership operations for a When process. */
public interface ClusterMembership extends AutoCloseable {
    void registerSelf(NodeInfo self, int workerId);

    void deregisterSelf();

    boolean tryBecomeController();

    void watchMembers(MemberEventHandler handler);

    List<NodeInfo> listNodes();

    Optional<String> currentController();

    /** ETCD mod revision of the election key, used as the Controller fencing term. */
    default OptionalLong currentControllerTerm() {
        return OptionalLong.empty();
    }

    @Override
    void close();
}
