package com.when.cluster.membership;

@FunctionalInterface
public interface MemberEventHandler {
    void onEvent(MemberEvent event);
}
