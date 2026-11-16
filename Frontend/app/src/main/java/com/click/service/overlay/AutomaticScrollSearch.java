package com.click.service.overlay;

import com.click.data.snapshot.NavigationState;
import com.click.data.snapshot.SettingsCapabilities;
import java.util.*;
import java.util.function.LongSupplier;

/** Per-episode budget; page/window/node churn never refunds spent attempts. */
final class AutomaticScrollSearch {
    enum Mode { SETTINGS, LAUNCHER }
    enum Status { MOVE, WAIT, FINISHED }
    static final class Plan {
        final Status status;final boolean forward;final String reason;
        Plan(Status status,boolean forward,String reason){this.status=status;this.forward=forward;this.reason=reason;}
    }
    private static final class Page {
        String last;boolean forward,started,stopped,sweptFromStart,forwardEndObserved;int unchanged;
        final Set<String> backwardViews=new HashSet<>(),forwardViews=new HashSet<>();
    }
    private final Mode mode;
    private final LongSupplier clock;
    private final Map<String,Page> pages=new HashMap<>();
    private String task;private int moves;private long startedAt;
    AutomaticScrollSearch(){this(Mode.SETTINGS);}
    AutomaticScrollSearch(Mode mode){this(mode,()->System.nanoTime()/1_000_000L);}
    AutomaticScrollSearch(Mode mode,LongSupplier clock){this.mode=mode;this.clock=clock;}
    Plan plan(String scope,NavigationState frame){return plan(scope,scope,frame);}
    Plan plan(String taskScope,String pageScope,NavigationState frame) {
        if(!Objects.equals(task,taskScope)){clear();task=taskScope;startedAt=clock.getAsLong();}
        Page p=pages.computeIfAbsent(pageScope,k->new Page());
        if(moves>=SettingsCapabilities.maxMoves(mode==Mode.LAUNCHER))return finish(p,"search_budget_exhausted");
        if(clock.getAsLong()-startedAt>=SettingsCapabilities.maxDuration(mode==Mode.LAUNCHER))return finish(p,"search_deadline_exhausted");
        if(p.stopped)return new Plan(Status.FINISHED,p.forward,"completed");
        if(frame==null || frame.fingerprint==null)return new Plan(Status.WAIT,p.forward,"observation_missing");
        if(!p.started){p.forward=mode==Mode.LAUNCHER || frame.atStart();p.sweptFromStart=frame.atStart();p.started=true;}
        if(!p.forward && frame.atStart()){
            p.forward=true;p.sweptFromStart=true;p.last=null;p.unchanged=0;
            if(mode==Mode.LAUNCHER && p.forwardEndObserved)return finish(p,"both_ends_observed");
        }
        if(p.forward && frame.atEnd()){
            p.forwardEndObserved=true;
            if(mode==Mode.SETTINGS && p.sweptFromStart)return finish(p,"both_ends_observed");
            p.forward=false;p.last=null;p.unchanged=0;
            if(frame.atStart())return finish(p,"no_scroll_range");
        }
        Set<String> seen=p.forward?p.forwardViews:p.backwardViews;
        if(Objects.equals(p.last,frame.fingerprint)){
            if(++p.unchanged<2)return new Plan(Status.WAIT,p.forward,"awaiting_movement");
            if(!p.forward && mode==Mode.SETTINGS){p.forward=true;p.last=null;p.unchanged=0;seen=p.forwardViews;}
            else if(p.forward && mode==Mode.LAUNCHER){p.forward=false;p.last=null;p.unchanged=0;seen=p.backwardViews;}
            else return finish(p,"no_progress");
        }else p.unchanged=0;
        if(p.last!=null && seen.contains(frame.fingerprint)){
            if(mode==Mode.LAUNCHER || p.forward)return finish(p,"cycle_observed");
            p.forward=true;p.last=null;seen=p.forwardViews;
        }
        seen.add(frame.fingerprint);p.last=frame.fingerprint;moves++;
        return new Plan(Status.MOVE,p.forward,"new_content");
    }
    private Plan finish(Page p,String reason){p.stopped=true;return new Plan(Status.FINISHED,p.forward,reason);}
    void clear(){task=null;moves=0;startedAt=0;pages.clear();}
}
