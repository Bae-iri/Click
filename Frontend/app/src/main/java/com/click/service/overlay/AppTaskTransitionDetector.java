package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;

/** Readonly native transition evidence; a changed route never proves a step or goal. */
public final class AppTaskTransitionDetector {
    public enum Kind { UNOBSERVABLE, CHANGING, STABLE_SAME, STABLE_CHANGED }
    public record Evidence(Kind kind,String beforeRoute,String afterRoute,boolean safeCurrent) { }
    public static final long STABLE_MS=350;
    private String candidateRoute,candidateObservation,candidateWindow;
    private long candidateCapturedAt,candidateStartedAt;

    public void reset(){candidateRoute=null;candidateObservation=null;candidateWindow=null;candidateCapturedAt=0;candidateStartedAt=0;}

    public static String currentRoute(ScreenSnapshot screen,long wallMs){
        try{
            if(screen==null||screen.activeWindowId==null||unsafeWindow(screen))return null;
            JSONObject observed=AppTaskObservation.capture(screen);long captured=observed.optLong("captured_at_ms");
            return "public".equals(observed.optString("status"))&&captured<=wallMs+2000&&wallMs-captured<=15000
                ?AppTaskObservation.routeKey(observed):null;
        }catch(Exception invalid){return null;}
    }

    public Evidence observe(ScreenSnapshot before,ScreenSnapshot after,long monotonicMs,long wallMs){
        try{
            if(before==null||after==null||monotonicMs<0||before.activeWindowId==null||after.activeWindowId==null
                    ||unsafeWindow(before)||unsafeWindow(after)){reset();return unavailable();}
            // Default native projection excludes candidate-dependent text and never uses OCR JSON.
            JSONObject original=AppTaskObservation.capture(before),current=AppTaskObservation.capture(after);
            long captured=current.optLong("captured_at_ms");
            if(!"public".equals(original.optString("status"))||!"public".equals(current.optString("status"))
                    ||!original.optString("package").equals(current.optString("package"))
                    ||original.optString("observation_id").equals(current.optString("observation_id"))
                    ||captured<original.optLong("captured_at_ms")||captured>wallMs+2000||wallMs-captured>15000){
                reset();return unavailable();
            }
            String beforeRoute=AppTaskObservation.routeKey(original),afterRoute=AppTaskObservation.routeKey(current);
            String window=after.activeWindowId+"|"+after.windowContext.windowKind()+"|"+after.windowContext.keyboardVisible();
            String observation=current.optString("observation_id");
            if(!afterRoute.equals(candidateRoute)||!window.equals(candidateWindow)||captured<candidateCapturedAt||monotonicMs<candidateStartedAt){
                candidateRoute=afterRoute;candidateWindow=window;candidateObservation=observation;
                candidateCapturedAt=captured;candidateStartedAt=monotonicMs;
                return new Evidence(Kind.CHANGING,beforeRoute,afterRoute,true);
            }
            if(!observation.equals(candidateObservation)&&captured-candidateCapturedAt>=STABLE_MS&&monotonicMs-candidateStartedAt>=STABLE_MS)
                return new Evidence(beforeRoute.equals(afterRoute)?Kind.STABLE_SAME:Kind.STABLE_CHANGED,beforeRoute,afterRoute,true);
            return new Evidence(Kind.CHANGING,beforeRoute,afterRoute,true);
        }catch(Exception invalid){reset();return unavailable();}
    }
    private static Evidence unavailable(){return new Evidence(Kind.UNOBSERVABLE,null,null,false);}
    private static boolean unsafeWindow(ScreenSnapshot screen){
        return !screen.privacyMaskingReady||java.util.Set.of("INPUT_METHOD","SYSTEM","OVERLAY").contains(screen.windowContext.windowKind());
    }
}
