package com.click.service.overlay;

import com.click.data.snapshot.NavigationState;
import com.click.data.snapshot.ScreenSnapshot;

/** Android test bridge to the production planner, including WAIT and native collection evidence. */
public final class NativeSettingsSearchPlanner {
    private final AutomaticScrollSearch search = new AutomaticScrollSearch();
    public static final class Plan {
        public final boolean finished, move, forward;
        public final String reason;
        private Plan(AutomaticScrollSearch.Plan plan) {
            finished = plan.status == AutomaticScrollSearch.Status.FINISHED;
            move = plan.status == AutomaticScrollSearch.Status.MOVE;
            forward = plan.forward;
            reason = plan.reason;
        }
    }
    public Plan next(String scope, ScreenSnapshot screen) {
        NavigationState frame = screen.navigation != null ? screen.navigation
                : new NavigationState("unknown", NavigationState.Axis.UNKNOWN, null, null,
                        -1, -1, -1, ObservationRecovery.navigationFingerprint(screen));
        return new Plan(search.plan(scope + ":" + frame.containerId, frame));
    }
}
