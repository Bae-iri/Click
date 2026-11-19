package com.click.data.snapshot;

import org.json.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** App-independent export of visible controls, after the user's explicit screen-guidance consent. */
public final class AppGuidePrivacy {
    private static final Pattern PRIVATE = Pattern.compile("password|passwd|credential|otp|account_number|credit_card|message_body|sender|recipient|email|phone_number|chat_message", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONAL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|https?://|\\b\\d[\\d .-]{7,}\\d\\b");
    private static String label(String value) {
        if(value==null)return "";
        String text=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").replaceAll("\\s+"," ").trim();
        return text.length()<=80&&!PERSONAL.matcher(text).find()?text:"";
    }
    private static boolean hidden(AccessibilityNodeSnapshot node,Map<String,AccessibilityNodeSnapshot> nodes) {
        Set<String> seen=new HashSet<>();
        while(node!=null&&seen.add(node.nodeId)) {
            if(node.sensitiveInput||PRIVATE.matcher(node.resourceId==null?"":node.resourceId).find())return true;
            node=nodes.get(node.parentId);
        }
        return false;
    }
    public JSONObject filter(ScreenSnapshot screen) throws Exception {
        JSONObject result=new JSONObject().put("status","outside_app")
            .put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight)
            .put("nodes",new JSONArray());
        if(screen==null||screen.packageName==null||screen.packageName.startsWith("com.clueclick.")
                ||screen.packageName.equals("com.click")||screen.packageName.equals("com.android.systemui"))return result;
        result.put("package_name",screen.packageName);
        Map<String,AccessibilityNodeSnapshot> nodes=new HashMap<>();
        for(var node:screen.nodes)nodes.put(node.nodeId,node);
        for(var node:screen.nodes)if(node.visibleToUser&&(node.passwordInput()
                ||node.sensitiveInput&&!node.emptySearchInput()))return result.put("status","sensitive");
        JSONArray exported=new JSONArray();
        for(var node:screen.nodes) {
            if(!node.visibleToUser||!node.enabled||!(node.clickable||node.scrollable)||hidden(node,nodes)
                    ||!screen.packageName.equals(node.packageName)
                    ||screen.activeWindowId!=null&&node.windowId!=screen.activeWindowId)continue;
            int[] b=node.bounds;
            if(b==null||b.length!=4||b[0]<0||b[1]<0||b[2]>screen.screenWidth||b[3]>screen.screenHeight||b[0]>=b[2]||b[1]>=b[3])continue;
            String text=label(node.contentDescription!=null?node.contentDescription:node.text);
            if(text.isEmpty())for(var child:screen.nodes)if(node.nodeId.equals(child.parentId)&&child.visibleToUser&&!hidden(child,nodes)) {
                text=label(child.text!=null?child.text:child.contentDescription);if(!text.isEmpty())break;
            }
            if(text.isEmpty())continue;
            exported.put(new JSONObject().put("node_id",node.nodeId).put("resource_id",node.resourceId==null?"":node.resourceId)
                .put("label",text).put("bounds",new JSONArray(b)).put("clickable",node.clickable).put("scrollable",node.scrollable));
            if(exported.length()==60)break;
        }
        return result.put("status",exported.length()==0?"unrecognized":"public").put("nodes",exported);
    }
}
