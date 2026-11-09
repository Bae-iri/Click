package com.click.service.overlay;

import com.click.data.snapshot.NavigationState;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Search by observed progress and endpoints. Accepted gestures are not movement proof. */
final class AutomaticScrollSearch {
    enum Mode { SETTINGS, LAUNCHER }
    enum Status { MOVE, WAIT, FINISHED }
    static final class Plan {
        final Status status; final boolean forward; final String reason;
        Plan(Status status, boolean forward, String reason){this.status=status;this.forward=forward;this.reason=reason;}
    }
    private final Mode mode;
    private String scope, last;
    private boolean forward, started, stopped, sweptFromStart, forwardEndObserved;
    private int unchanged;
    private final Set<String> backwardViews=new HashSet<>(),forwardViews=new HashSet<>();
    AutomaticScrollSearch(){this(Mode.SETTINGS);}
    AutomaticScrollSearch(Mode mode){this.mode=mode;}

    Plan plan(String scope, NavigationState frame) {
        if(!Objects.equals(this.scope,scope)){clear();this.scope=scope;}
        if(stopped)return new Plan(Status.FINISHED,forward,"completed");
        if(frame==null || frame.fingerprint==null)return new Plan(Status.WAIT,forward,"observation_missing");
        if(!started){
            forward=mode==Mode.LAUNCHER || frame.atStart();
            sweptFromStart=frame.atStart();started=true;
        }
        if(!forward && frame.atStart()){
            forward=true;sweptFromStart=true;last=null;unchanged=0;
            if(mode==Mode.LAUNCHER && forwardEndObserved)return finish("both_ends_observed");
        }
        if(forward && frame.atEnd()){
            forwardEndObserved=true;
            if(mode==Mode.SETTINGS && sweptFromStart)return finish("both_ends_observed");
            forward=false;last=null;unchanged=0;
            if(frame.atStart())return finish("no_scroll_range");
        }
        Set<String> seen=forward?forwardViews:backwardViews;
        if(Objects.equals(last,frame.fingerprint)){
            if(++unchanged<2)return new Plan(Status.WAIT,forward,"awaiting_movement");
            if(!forward && mode==Mode.SETTINGS){
                // No native backward movement: try the other direction, but do
                // not claim the start of the collection was actually reached.
                forward=true;last=null;unchanged=0;seen=forwardViews;
            } else if(forward && mode==Mode.LAUNCHER){forward=false;last=null;unchanged=0;seen=backwardViews;}
            else return finish("no_progress");
        }else unchanged=0;
        if(last!=null && seen.contains(frame.fingerprint)){
            // A wraparound pager returned to a visited page in this direction.
            if(mode==Mode.LAUNCHER || forward)return finish("cycle_observed");
            forward=true;last=null;seen=forwardViews;
        }
        seen.add(frame.fingerprint);last=frame.fingerprint;
        return new Plan(Status.MOVE,forward,"new_content");
    }
    private Plan finish(String reason){stopped=true;return new Plan(Status.FINISHED,forward,reason);}
    void clear(){scope=null;last=null;started=stopped=sweptFromStart=forwardEndObserved=false;unchanged=0;forward=false;backwardViews.clear();forwardViews.clear();}
}
