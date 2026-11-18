package com.click.data.snapshot;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/** Exports only public semantic login labels. Credentials and identities stay local. */
public final class Gov24LoginPrivacy {
    public static final String PACKAGE="kr.go.minwon.m";
    private static final JSONObject POLICY;
    static {try {POLICY=new JSONObject(com.click.generated.Gov24LoginPolicyData.json());}
        catch(JSONException error){throw new ExceptionInInitializerError(error);}}
    public static String normalize(String value) {
        return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]","");
    }
    private static boolean exact(JSONArray list,String label) {
        for(int i=0;list!=null&&i<list.length();i++)if(normalize(list.optString(i)).equals(normalize(label)))return true;
        return false;
    }
    public static String option(String label) {
        JSONObject specs=POLICY.optJSONObject("options");
        for(var keys=specs.keys();keys.hasNext();) {
            String key=keys.next();if(exact(specs.optJSONObject(key).optJSONArray("aliases"),label))return key;
        }
        return null;
    }
    private JSONObject blocked(JSONObject base,String status,String reason) throws JSONException {
        base.put("status",status).put("nodes",new JSONArray());
        if(reason!=null)base.put("handoff_reason",reason);
        return base;
    }
    public JSONObject filter(ScreenSnapshot screen) throws Exception {
        JSONObject base=new JSONObject().put("status","unrecognized").put("nodes",new JSONArray())
            .put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight);
        if(screen==null)return base;
        if(!PACKAGE.equals(screen.packageName))return exact(POLICY.optJSONArray("auth_packages"),screen.packageName)
            ?blocked(base,"handoff","external_auth"):blocked(base,"outside_app",null);
        base.put("package_name",PACKAGE);
        Map<String,List<JSONObject>> options=new LinkedHashMap<>();
        JSONObject specs=POLICY.getJSONObject("options");
        for(AccessibilityNodeSnapshot n:screen.nodes) {
            if(!n.visibleToUser)continue;
            if(!PACKAGE.equals(n.packageName)||screen.activeWindowId!=null&&n.windowId!=screen.activeWindowId)return blocked(base,"sensitive",null);
            if(n.sensitiveInput||n.passwordInput())return blocked(base,"handoff","credential_input");
            String label=n.text!=null?n.text:n.contentDescription;
            if(label!=null&&exact(POLICY.optJSONArray("consent_labels"),label))return blocked(base,"handoff","consent_required");
            if(label!=null&&exact(POLICY.optJSONArray("handoff_labels"),label))return blocked(base,"handoff","certificate_selection");
            String key=option(label);
            if(key==null||!n.clickable||!n.enabled||n.checkable||n.scrollable)continue;
            int[] b=n.bounds;
            if(b==null||b.length!=4||b[0]<0||b[1]<0||b[2]>screen.screenWidth||b[3]>screen.screenHeight||b[0]>=b[2]||b[1]>=b[3])continue;
            JSONObject node=new JSONObject().put("node_id",n.nodeId).put("option_id",key)
                .put("resource_id","").put("label",specs.getJSONObject(key).getString("label"))
                .put("bounds",new JSONArray(b)).put("clickable",true).put("scrollable",false);
            options.computeIfAbsent(key,k->new ArrayList<>()).add(node);
        }
        // A modal provider grid takes precedence over the underlying login page.
        boolean provider=options.keySet().stream().anyMatch(k->k.startsWith("provider_"));
        JSONArray exported=new JSONArray();
        for(var entry:options.entrySet())if(entry.getValue().size()==1&&(!provider||entry.getKey().startsWith("provider_")))exported.put(entry.getValue().get(0));
        if(exported.length()==0||exported.length()>POLICY.getInt("max_nodes"))return base;
        return base.put("status","public").put("nodes",exported);
    }
}
