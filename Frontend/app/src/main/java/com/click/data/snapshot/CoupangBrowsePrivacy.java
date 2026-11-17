package com.click.data.snapshot;

import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Public, native Coupang affordances only; unknown and personal content stays on device. */
public final class CoupangBrowsePrivacy {
    private static final JSONObject policy;
    static { try { policy=new JSONObject(com.click.generated.CoupangBrowsePolicyData.json()); }
        catch(JSONException error){throw new ExceptionInInitializerError(error);} }
    public CoupangBrowsePrivacy(Context context) throws Exception {
    }
    public static String resource(String value) {
        String name=value==null?"":value.substring(value.lastIndexOf('/')+1);
        return name.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
    }
    private static boolean has(String key,String value) {
        JSONArray list=policy.optJSONArray(key);
        for(int i=0;list!=null && i<list.length();i++)if(value.equals(list.optString(i)))return true;
        return false;
    }
    private boolean contains(String key,String value) {
        JSONArray list=policy.optJSONArray(key);
        for(int i=0;list!=null && i<list.length();i++)if(value.toLowerCase(Locale.ROOT).contains(list.optString(i)))return true;
        return false;
    }
    public static String queryHash(String value) throws Exception {
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+","").toLowerCase(Locale.ROOT);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:hash)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    public static String captureQueryHash(String pkg,String id,String value,boolean password) {
        // Generic EditText hashes stay local until filter() proves a public search ancestor.
        if(password || !policy.optString("package").equals(pkg)
                || !(has("query_resources",resource(id)) || has("contextual_query_resources",resource(id)))
                || value==null || value.isBlank())return null;
        try { return queryHash(value); }catch(Exception unavailable){return null;}
    }
    private static String publicLabel(String value) {
        if(value==null)return "";
        String text=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","");
        if(text.length()>160 || text.matches("(?s).*(?:[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|https?://|www\\.|0[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}|[0-9]{7,}).*"))return "";
        return text.replaceAll("\\s+"," ").trim();
    }
    private JSONObject blocked(JSONObject base,String status) throws JSONException {
        base.put("status",status).put("nodes",new JSONArray());base.remove("query_hash");return base;
    }
    public JSONObject filter(ScreenSnapshot screen) throws Exception {
        JSONObject base=new JSONObject().put("status","unrecognized").put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight).put("nodes",new JSONArray());
        if(screen==null || !policy.getString("package").equals(screen.packageName))return blocked(base,"outside_app");
        base.put("package_name",screen.packageName);
        JSONArray exported=new JSONArray();Set<String> ids=new HashSet<>();
        Map<String,AccessibilityNodeSnapshot> byId=new HashMap<>();
        for(var node:screen.nodes)byId.put(node.nodeId,node);
        for(AccessibilityNodeSnapshot node:screen.nodes) {
            if(!node.visibleToUser)continue;
            if(!screen.packageName.equals(node.packageName) || screen.activeWindowId!=null && node.windowId!=screen.activeWindowId)return blocked(base,"sensitive");
            String name=resource(node.resourceId),label=node.text!=null?node.text:node.contentDescription;
            if(has("private_page_resources",name))return blocked(base,"sensitive");
            if(label!=null && has("blocked_titles",label.trim()))return blocked(base,"sensitive");
            boolean query=has("query_resources",name);
            if(!query && has("contextual_query_resources",name)) {
                boolean publicPage=false;
                for(var candidate:screen.nodes)if(candidate.visibleToUser && has("contextual_query_pages",resource(candidate.resourceId)))publicPage=true;
                Set<String> seen=new HashSet<>();String parent=node.parentId;
                while(publicPage && parent!=null && seen.add(parent)) {
                    var ancestor=byId.get(parent);if(ancestor==null)break;
                    if(contains("blocked_resource_tokens",resource(ancestor.resourceId)))break;
                    if(has("search_resources",resource(ancestor.resourceId))){query=true;break;}
                    parent=ancestor.parentId;
                }
            }
            if(node.passwordInput() || node.sensitiveInput && !query)return blocked(base,"sensitive");
            if(contains("blocked_resource_tokens",name))continue;
            Set<String> parents=new HashSet<>();String parent=node.parentId;boolean hidden=false;
            while(parent!=null && parents.add(parent)) {
                var ancestor=byId.get(parent);if(ancestor==null)break;
                if(contains("blocked_resource_tokens",resource(ancestor.resourceId))){hidden=true;break;}
                parent=ancestor.parentId;
            }
            if(hidden)continue;
            if(query && node.publicBrowseQueryHash!=null)base.put("query_hash",node.publicBrowseQueryHash);
            if(node.resourceId==null || !node.resourceId.startsWith(screen.packageName+":id/"))continue;
            if(!(has("search_resources",name)||has("product_resources",name)||has("results_resources",name)
                    ||has("detail_resources",name)||has("title_resources",name)))continue;
            int[] b=node.bounds;
            if(b==null || b.length!=4 || b[0]<0 || b[1]<0 || b[2]>screen.screenWidth || b[3]>screen.screenHeight || b[0]>=b[2] || b[1]>=b[3])continue;
            if(!ids.add(node.nodeId))return blocked(base,"unrecognized");
            String safe=query?"":publicLabel(label);
            if(contains("blocked_labels",safe))continue;
            exported.put(new JSONObject().put("node_id",node.nodeId).put("resource_id",node.resourceId)
                .put("label",safe).put("bounds",new JSONArray(b)).put("clickable",node.clickable&&node.enabled)
                .put("scrollable",node.scrollable&&node.enabled));
            if(exported.length()>policy.getInt("max_nodes"))return blocked(base,"unrecognized");
        }
        if(exported.length()==0)return blocked(base,"unrecognized");
        return base.put("status","public").put("nodes",exported);
    }
}
