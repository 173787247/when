package com.when.ingress.id;

/** Generates globally unique, externally stable message identifiers. */
@FunctionalInterface
public interface MessageIdGenerator {
    String nextId();
}
