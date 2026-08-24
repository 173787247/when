package com.when.core;

import java.util.Collection;

/** Lookup boundary for time-wheel instances hosted by the current node. */
public interface TimeWheelRegistry {
    TimeWheel require(String timeWheelId);

    Collection<TimeWheel> all();
}
