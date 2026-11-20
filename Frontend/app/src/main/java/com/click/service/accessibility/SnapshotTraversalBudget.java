package com.click.service.accessibility;

import android.os.SystemClock;
import java.util.function.LongSupplier;

/** Bounds native binder reads before filtering; an exceeded budget invalidates the whole observation. */
final class SnapshotTraversalBudget {
    static final int MAX_NODES=2000, MAX_DEPTH=64;
    static final long MAX_MS=200;
    static final class Incomplete extends RuntimeException { }
    private final int maxNodes,maxDepth;
    private final long end;
    private final LongSupplier clock;
    private int nodes,probes,total;

    SnapshotTraversalBudget(){this(MAX_NODES,MAX_DEPTH,MAX_MS,SystemClock::elapsedRealtime);}
    SnapshotTraversalBudget(int maxNodes,int maxDepth,long duration,LongSupplier clock) {
        this.maxNodes=maxNodes;this.maxDepth=maxDepth;this.clock=clock;end=clock.getAsLong()+duration;
    }
    void check(){if(clock.getAsLong()>=end || Thread.currentThread().isInterrupted())throw new Incomplete();}
    void visit(int depth){
        check();
        if(depth>maxDepth || ++nodes>maxNodes || ++total>maxNodes*3)throw new Incomplete();
    }
    void child(){check();if(++probes>maxNodes)throw new Incomplete();}
    void nextPass(){check();nodes=0;probes=0;}
}
