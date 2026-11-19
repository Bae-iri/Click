package com.click.data.snapshot;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Controls and state across apps. Input values and images never enter this projection. */
public final class AppTaskObservation {
    private static final Pattern PRIVATE=Pattern.compile("password|passwd|credential|otp|account_number|credit_card|message_body|sender|recipient|email|phone_number|chat_message",Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONAL=Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|https?://|\\b\\d[\\d .-]{7,}\\d\\b");
    public static String label(String value){
        if(value==null)return "";
        String s=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").replaceAll("\\s+"," ").trim();
        return s.length()<=80&&!PERSONAL.matcher(s).find()?s:"";
    }
    public static boolean searchField(AccessibilityNodeSnapshot n){
        return !n.passwordInput()&&(n.publicSearchInput()||n.sensitiveInput&&n.resourceId!=null
            &&n.resourceId.toLowerCase(Locale.ROOT).matches(".*(?:search|query|url_bar|omnibox).*"));
    }
    private static boolean hidden(AccessibilityNodeSnapshot n,Map<String,AccessibilityNodeSnapshot> byId){
        Set<String> seen=new HashSet<>();
        while(n!=null&&seen.add(n.nodeId)){
            if(n.passwordInput()||n.sensitiveInput&&!searchField(n)||PRIVATE.matcher(n.resourceId==null?"":n.resourceId).find())return true;
            n=byId.get(n.parentId);
        }
        return false;
    }
    private static boolean descendantOf(AccessibilityNodeSnapshot child,String parent,Map<String,AccessibilityNodeSnapshot> byId){
        Set<String> seen=new HashSet<>();String id=child.parentId;
        while(id!=null&&seen.add(id)){if(parent.equals(id))return true;AccessibilityNodeSnapshot n=byId.get(id);id=n==null?null:n.parentId;}
        return false;
    }
    public static String hash(String text){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static JSONObject capture(ScreenSnapshot screen)throws JSONException {
        JSONObject out=new JSONObject().put("status","outside").put("package",screen==null||screen.packageName==null?"":screen.packageName)
            .put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("keyboard_visible",screen!=null&&screen.windowContext.keyboardVisible())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight).put("nodes",new JSONArray());
        if(screen==null||screen.packageName==null||screen.packageName.startsWith("com.clueclick.")||screen.packageName.equals("com.click")||screen.packageName.equals("com.android.systemui"))return finish(out);
        if(!screen.privacyMaskingReady)return finish(out.put("status","protected"));
        Map<String,AccessibilityNodeSnapshot> byId=new HashMap<>();for(var n:screen.nodes)byId.put(n.nodeId,n);
        for(var n:screen.nodes)if(n.visibleToUser&&(n.passwordInput()||n.sensitiveInput&&!searchField(n)))return finish(out.put("status","protected"));
        JSONArray nodes=new JSONArray();int headings=0;
        for(var n:screen.nodes){
            if(!n.visibleToUser||!(n.enabled||searchField(n)&&n.publicSearchEnabled())||hidden(n,byId)||!screen.packageName.equals(n.packageName)||screen.activeWindowId!=null&&n.windowId!=screen.activeWindowId)continue;
            boolean editable=searchField(n),heading=n.heading&&headings<6;
            if(!n.clickable&&!n.scrollable&&!editable&&!heading)continue;
            int[] b=n.bounds;if(b==null||b.length!=4||b[0]<0||b[1]<0||b[2]>screen.screenWidth||b[3]>screen.screenHeight||b[0]>=b[2]||b[1]>=b[3])continue;
            String text=editable?"검색어 입력":label(n.contentDescription!=null?n.contentDescription:n.text);
            if(text.isEmpty())for(var child:screen.nodes)if(n.nodeId.equals(child.parentId)&&child.visibleToUser&&!hidden(child,byId)){
                text=label(child.text!=null?child.text:child.contentDescription);if(!text.isEmpty())break;
            }
            String cls=n.className==null?"":n.className.toLowerCase(Locale.ROOT),resource=n.resourceId==null?"":n.resourceId;
            String role=editable?"text_field":n.checkable?"toggle":cls.contains("tab")||resource.toLowerCase(Locale.ROOT).contains("tab_")?"tab":n.scrollable?"list":n.clickable?"button":heading?"text":"unknown";
            if(text.isEmpty()&&n.scrollable)text="목록";
            if(text.isEmpty()&&!n.clickable&&!editable)continue;
            JSONObject node=new JSONObject().put("id",n.nodeId).put("label",text).put("resource",resource).put("role",role)
                .put("bounds",new JSONArray(b)).put("clickable",n.clickable).put("editable",editable).put("scrollable",n.scrollable)
                .put("selected",n.selected).put("checked",n.checked==null?JSONObject.NULL:n.checked).put("has_value",editable&&n.hasInputContent()).put("source","accessibility");
            if(n.scrollable){StringBuilder viewport=new StringBuilder();for(var child:screen.nodes)if(child.visibleToUser&&descendantOf(child,n.nodeId,byId)&&!hidden(child,byId))viewport.append(child.nodeId).append(Arrays.toString(child.bounds)).append(hash(String.valueOf(child.text)));node.put("viewport_hash",hash(viewport.toString()));}
            nodes.put(node);if(heading)headings++;if(nodes.length()==80)break;
        }
        return finish(out.put("status",nodes.length()==0?"empty":"public").put("nodes",nodes));
    }
    public static JSONObject finish(JSONObject screen)throws JSONException {
        return screen.put("fingerprint",hash(screen.optString("package")+"|"+screen.optString("status")+"|"+screen.getJSONArray("nodes")));
    }
    public static JSONObject find(JSONObject screen,String id){JSONArray nodes=screen.optJSONArray("nodes");for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&id.equals(n.optString("id")))return n;}return null;}
}
