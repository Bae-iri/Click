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
    /** Local-only contract: never add these options to the strict wire schema. */
    private static final class ProjectionSpec {
        final Set<String> resultLabels;
        ProjectionSpec(Set<String> requested){
            Set<String> copy=new LinkedHashSet<>();
            if(requested!=null)for(String value:requested){String normalized=label(value);if(!normalized.isEmpty())copy.add(normalized);}
            resultLabels=Collections.unmodifiableSet(copy);
        }
    }
    /** JSONObject serialization contains only existing protocol fields, not the local spec. */
    private static final class ProjectedObservation extends JSONObject {
        final ProjectionSpec spec;
        ProjectedObservation(ProjectionSpec spec){this.spec=spec;}
    }
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
    private static final Pattern APP_BAR=Pattern.compile("(?:^|[:/_])(?:action_?bar|tool_?bar|app_?bar|collapsing_app_bar)(?:$|_)",Pattern.CASE_INSENSITIVE);
    /**
     * A page's own title: plain text inside the app bar/toolbar, with no actionable ancestor.
     * It names the destination page (public UI), unlike body text, so it is exported like a heading.
     */
    static boolean pageTitle(AccessibilityNodeSnapshot n,Map<String,AccessibilityNodeSnapshot> byId){
        if(n.clickable||n.scrollable||n.sensitiveInput||n.checkable||n.text==null||n.text.trim().isEmpty())return false;
        Set<String> seen=new HashSet<>();boolean inBar=false;AccessibilityNodeSnapshot current=byId.get(n.parentId);int depth=0;
        while(current!=null&&seen.add(current.nodeId)&&depth++<4){
            if(current.clickable)return false;
            if(current.resourceId!=null&&APP_BAR.matcher(current.resourceId).find())inBar=true;
            current=byId.get(current.parentId);
        }
        return inBar;
    }
    private static boolean nativeTab(AccessibilityNodeSnapshot node){
        String cls=node.className==null?"":node.className.toLowerCase(Locale.ROOT);
        String resource=node.resourceId==null?"":node.resourceId.toLowerCase(Locale.ROOT);
        return cls.contains("tab")||resource.contains("tab_");
    }
    /** Selected tabs remain public result evidence even when Android disables their click action. */
    private static boolean selectedTabEvidence(AccessibilityNodeSnapshot node){
        String cls=node.className==null?"":node.className.toLowerCase(Locale.ROOT);
        return node.selected&&nativeTab(node)&&!node.clickable&&!node.scrollable&&!node.checkable&&!searchField(node)
            &&!cls.endsWith("tablayout")&&!cls.endsWith("tabwidget")&&!cls.contains("table");
    }
    public static String hash(String text){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static JSONObject capture(ScreenSnapshot screen)throws JSONException {
        return capture(screen,Collections.emptySet());
    }
    /** Export only requested public result labels, never all the page's static text. */
    public static JSONObject capture(ScreenSnapshot screen,Set<String> resultLabels)throws JSONException {
        return capture(screen,new ProjectionSpec(resultLabels));
    }
    /**
     * Reobserve under the proposal's exact local projection, retaining every
     * fingerprint check. Never infer the contract from model/copy JSON nodes.
     * Legacy/plain JSON uses the default projection; a lost enriched contract
     * therefore fails closed rather than silently weakening the comparison.
     */
    public static JSONObject recaptureLike(ScreenSnapshot screen,JSONObject reference)throws JSONException {
        return reference instanceof ProjectedObservation
            ?capture(screen,((ProjectedObservation)reference).spec):capture(screen);
    }
    private static JSONObject capture(ScreenSnapshot screen,ProjectionSpec spec)throws JSONException {
        Set<String> resultLabels=spec.resultLabels;
        JSONObject out=new ProjectedObservation(spec).put("status","outside").put("package",screen==null||screen.packageName==null?"":screen.packageName)
            .put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("keyboard_visible",screen!=null&&screen.windowContext.keyboardVisible())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight).put("nodes",new JSONArray());
        if(screen==null||screen.packageName==null||screen.packageName.startsWith("com.clueclick.")||screen.packageName.equals("com.click")||screen.packageName.equals("com.android.systemui"))return finish(out);
        if(!screen.privacyMaskingReady)return finish(out.put("status","protected"));
        Map<String,AccessibilityNodeSnapshot> byId=new HashMap<>();for(var n:screen.nodes)byId.put(n.nodeId,n);
        for(var n:screen.nodes)if(n.visibleToUser&&(n.passwordInput()||n.sensitiveInput&&!searchField(n)))return finish(out.put("status","protected"));
        JSONArray nodes=new JSONArray();int headings=0;Set<String> resultTextIds=new HashSet<>();
        List<AccessibilityNodeSnapshot> ordered=new ArrayList<>(screen.nodes);
        ordered.sort(Comparator.comparingInt(n->resultLabels.contains(label(n.contentDescription!=null?n.contentDescription:n.text))?0:1));
        for(var n:ordered){
            boolean selectedEvidence=selectedTabEvidence(n),switchEvidence=n.checkable&&n.checked!=null;
            boolean readOnlyState=(selectedEvidence||switchEvidence)&&!n.enabled;
            if(!n.visibleToUser||!(n.enabled||searchField(n)&&n.publicSearchEnabled()||selectedEvidence||switchEvidence)||hidden(n,byId)||!screen.packageName.equals(n.packageName)||screen.activeWindowId!=null&&n.windowId!=screen.activeWindowId)continue;
            boolean editable=!readOnlyState&&searchField(n),clickable=!readOnlyState&&n.clickable,scrollable=!readOnlyState&&n.scrollable;
            boolean heading=(n.heading||pageTitle(n,byId))&&headings<6;
            boolean resultLabel=!editable&&resultLabels.contains(label(n.contentDescription!=null?n.contentDescription:n.text));
            if(!clickable&&!scrollable&&!editable&&!heading&&!resultLabel&&!selectedEvidence&&!switchEvidence)continue;
            int[] b=n.bounds;if(b==null||b.length!=4||b[0]<0||b[1]<0||b[2]>screen.screenWidth||b[3]>screen.screenHeight||b[0]>=b[2]||b[1]>=b[3])continue;
            String text=editable?"검색어 입력":label(n.contentDescription!=null?n.contentDescription:n.text);
            if(text.isEmpty())for(var child:screen.nodes)if(n.nodeId.equals(child.parentId)&&child.visibleToUser&&!hidden(child,byId)){
                text=label(child.text!=null?child.text:child.contentDescription);if(!text.isEmpty())break;
            }
            String resource=n.resourceId==null?"":n.resourceId;
            String role=editable?"text_field":n.checkable?"toggle":nativeTab(n)?"tab":scrollable?"list":clickable?"button":heading||resultLabel?"text":"unknown";
            if(text.isEmpty()&&scrollable)text="목록";
            if(text.isEmpty()&&!clickable&&!editable)continue;
            JSONObject node=new JSONObject().put("id",n.nodeId).put("label",text).put("resource",resource).put("role",role)
                .put("bounds",new JSONArray(b)).put("clickable",clickable).put("editable",editable).put("scrollable",scrollable)
                .put("selected",n.selected).put("checked",n.checked==null?JSONObject.NULL:n.checked).put("has_value",editable&&n.hasInputContent()).put("source","accessibility");
            if(n.heading&&"text".equals(role))node.put("native_heading",true);
            if(resultLabel&&!clickable&&!scrollable&&!editable)resultTextIds.add(n.nodeId);
            if(scrollable){StringBuilder viewport=new StringBuilder();for(var child:screen.nodes)if(child.visibleToUser&&descendantOf(child,n.nodeId,byId)&&!hidden(child,byId))viewport.append(child.nodeId).append(Arrays.toString(child.bounds)).append(hash(String.valueOf(child.text)));node.put("viewport_hash",hash(viewport.toString()));}
            nodes.put(node);if(heading)headings++;if(nodes.length()==80)break;
        }
        // A button and its label child describe one result, not two ambiguous targets.
        // Equal labels in separate rows remain distinct and therefore ambiguous.
        JSONArray distinct=new JSONArray();
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i);boolean represented=false;
            if(resultTextIds.contains(n.getString("id")))for(int j=0;j<nodes.length();j++){
                JSONObject owner=nodes.getJSONObject(j);
                // A passive scroll layout can repeat a descendant page title;
                // its borrowed label does not replace the descendant's proof.
                boolean passiveScrollContainer=owner.optBoolean("scrollable")&&!owner.optBoolean("clickable")
                    &&!owner.optBoolean("editable")&&!Set.of("toggle","tab").contains(owner.optString("role"));
                if(i!=j&&n.optString("label").equals(owner.optString("label"))
                        &&!passiveScrollContainer
                        &&descendantOf(byId.get(n.getString("id")),owner.getString("id"),byId)){represented=true;break;}
            }
            if(!represented)distinct.put(n);
        }
        nodes=distinct;
        return finish(out.put("status",nodes.length()==0?"empty":"public").put("nodes",nodes));
    }
    public static JSONObject finish(JSONObject screen)throws JSONException {
        return screen.put("fingerprint",hash(screen.optString("package")+"|"+screen.optString("status")+"|"+screen.getJSONArray("nodes")));
    }
    public static JSONObject find(JSONObject screen,String id){JSONArray nodes=screen.optJSONArray("nodes");for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&id.equals(n.optString("id")))return n;}return null;}
    public static String targetKey(JSONObject node){return hash(node.optString("role")+"\n"+node.optString("resource")+"\n"+node.optString("label"));}
    /** Stable across node paths, display geometry and clocks; no raw content is exported. */
    /**
     * Route-evidence key: interactive controls and their native state only. Static result/heading
     * text (added when completion or expectation labels are projected) and scroll viewport hashes
     * (animation, feed content) are excluded, so the same page keeps one key across the
     * verification, result and recall projections. Mirrored by Backend screen_key().
     */
    public static String routeKey(JSONObject screen)throws JSONException {
        List<String> keys=new ArrayList<>();JSONArray nodes=screen.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);String role=n.optString("role");
            if(!n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable")&&!"toggle".equals(role)&&!"tab".equals(role))continue;
            keys.add(targetKey(n)+"|"+(n.optBoolean("clickable")?"1":"0")+(n.optBoolean("editable")?"1":"0")+(n.optBoolean("scrollable")?"1":"0")
                +"|"+(n.optBoolean("selected")?"1":"0")+"|"+(n.isNull("checked")?"null":String.valueOf(n.optBoolean("checked")))+"|"+(n.optBoolean("has_value")?"1":"0"));}
        Collections.sort(keys);return hash("route.v2\n"+screen.optString("package")+"\n"+screen.optString("status")+"\n"+String.join("\n",keys));
    }
    public static String learningKey(JSONObject screen)throws JSONException {
        List<String> keys=new ArrayList<>();JSONArray nodes=screen.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);keys.add(targetKey(n)+"|"+(n.optBoolean("clickable")?"1":"0")+(n.optBoolean("editable")?"1":"0")+(n.optBoolean("scrollable")?"1":"0")
            +"|"+(n.optBoolean("selected")?"1":"0")+"|"+(n.isNull("checked")?"null":String.valueOf(n.optBoolean("checked")))+"|"+(n.optBoolean("has_value")?"1":"0")+"|"+n.optString("viewport_hash"));}
        Collections.sort(keys);return hash(screen.optString("package")+"\n"+screen.optString("status")+"\n"+String.join("\n",keys));
    }
}
