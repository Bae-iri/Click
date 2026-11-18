package com.click.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.click.data.snapshot.Gov24LoginPrivacy;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import org.json.JSONObject;

/** Shell-only debug probe. Reports public states and counts, never credentials or screen text. */
public final class Gov24GuideProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        JSONObject report=new JSONObject();
        try {
            report.put("application_id",context.getPackageName());
            report.put("label",context.getApplicationInfo().loadLabel(context.getPackageManager()));
            report.put("signed_in",new com.click.data.local.AuthTokenStore(context).hasRefreshToken());
            report.put("accessibility_connected",ClickAccessibilityService.accessibilityWindowManager()!=null);
            var screen=ClickAccessibilityService.freshSnapshot();
            JSONObject safe=new Gov24LoginPrivacy().filter(screen);
            report.put("screen_status",safe.optString("status")).put("public_node_count",safe.getJSONArray("nodes").length());
            report.put("handoff_reason",safe.optString("handoff_reason"));
            var home=ClickAccessibilityService.gov24LoginEntry();
            if(home!=null) {
                report.put("raw_filter_status",safe.optString("status"));
                report.put("screen_status","public_home").put("handoff_reason","");
                report.put("local_login_visible",home.visible);
            }
            Object service=((java.lang.ref.WeakReference<?>)field(ClickOverlayService.class,"runningService",null)).get();
            report.put("overlay_running",service!=null);
            if(service!=null) {
                for(String key:new String[]{"gov24Guide","appForeground","closing","taskFlowCollapsed"})
                    report.put(key,field(ClickOverlayService.class,key,service));
                Object controller=field(ClickOverlayService.class,"coupangBrowse",service);
                report.put("controller_exists",controller!=null);
                if(controller!=null) {
                    if(controller instanceof com.click.service.overlay.CoupangBrowseController flow)
                        report.put("controller_status",flow.status()).put("controller_outcome",flow.outcome())
                            .put("confirmed_handoff_reason",flow.handoffReason()).put("settling_private_screen",flow.settlingPrivateScreen());
                    for(String key:new String[]{"busy","terminal","entryObserved","version"})
                        report.put(key,field(controller.getClass(),key,controller));
                    report.put("awaiting_answer",field(controller.getClass(),"question",controller)!=null);
                    Object response=field(controller.getClass(),"guide",controller);
                    if(response instanceof JSONObject) {
                        report.put("subgoal",((JSONObject)response).optString("subgoal"));
                        report.put("outcome",((JSONObject)response).optString("outcome"));
                    }
                }
            }
        }catch(Exception error){try{report.put("probe_error",error.getClass().getSimpleName());}catch(Exception ignored){}}
        setResultData(report.toString());
    }
    private Object field(Class<?> type,String name,Object owner) throws Exception {
        java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
}
