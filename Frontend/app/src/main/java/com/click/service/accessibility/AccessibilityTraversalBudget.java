package com.click.service.accessibility;

import android.os.SystemClock;
import java.util.function.LongSupplier;

/** Shared limits for every traversal contributing to one native observation. */
final class AccessibilityTraversalBudget {
    static final int MAX_NODES=2000, MAX_DEPTH=64;
    static final long MAX_MS=150;
    static final class Exhausted extends RuntimeException {}
    private final LongSupplier clock;
    private final long deadline;
    private final int maxNodes,maxDepth;
    private int visited;
    AccessibilityTraversalBudget(){this(MAX_NODES,MAX_DEPTH,MAX_MS,SystemClock::elapsedRealtime);}
    AccessibilityTraversalBudget(int nodes,int depth,long milliseconds,LongSupplier clock) {
        this.maxNodes=nodes;this.maxDepth=depth;this.clock=clock;deadline=clock.getAsLong()+milliseconds;
    }
    void check(){if(clock.getAsLong()>=deadline)throw new Exhausted();}
    void take(int depth) {
        check();
        if(depth>maxDepth||++visited>maxNodes)throw new Exhausted();
    }
}
