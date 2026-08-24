package com.when.observability;

/** Transport-neutral span kinds exposed by the observability facade. */
public enum TraceSpanKind {
    INTERNAL,
    SERVER,
    CLIENT
}
