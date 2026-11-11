package com.click.data.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Last check before HTTP: only reviewed settings labels, structure and numeric control values. */
public final class SettingsScreenPrivacy {
    public static final class Blocked extends IOException {
        public enum Reason { SENSITIVE_INPUT, SENSITIVE_SCREEN, UNSUPPORTED_APP, UNVERIFIED_SCREEN, VISION_MASK_UNAVAILABLE }
        public final Reason reason;
        public Blocked() { this(Reason.UNVERIFIED_SCREEN); }
        public Blocked(Reason reason) { super("SCREEN_PRIVACY_BLOCKED"); this.reason = reason; }
    }
    public static final class Incomplete extends IOException {
        public Incomplete() { super("SCREEN_OBSERVATION_INCOMPLETE"); }
    }

    private final VisionPrivacy vocabulary;
    private final Set<String> settings, launchers, blockedTitles, descriptions, privateResources, privateRegions, emptySearch, roles;

    public SettingsScreenPrivacy(VisionPrivacy vocabulary, JSONObject policy) throws JSONException {
        if (policy.getInt("version") != 1) throw new JSONException("Unsupported screen policy");
        this.vocabulary = vocabulary;
        settings = strings(policy.getJSONArray("settings_packages"));
        launchers = strings(policy.getJSONArray("launcher_packages"));
        blockedTitles = strings(policy.getJSONArray("blocked_titles"));
        descriptions = strings(policy.getJSONArray("description_resources"));
        privateResources = strings(policy.getJSONArray("private_resource_tokens"));
        privateRegions = strings(policy.optJSONArray("private_region_tokens") == null
                ? new JSONArray() : policy.getJSONArray("private_region_tokens"));
        emptySearch = strings(policy.getJSONArray("empty_search_resources"));
        roles = strings(policy.getJSONArray("roles"));
    }

    public static SettingsScreenPrivacy load(InputStream vocabulary, InputStream policy) throws IOException, JSONException {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = policy.read(buffer)) != -1) data.write(buffer, 0, count);
        return new SettingsScreenPrivacy(VisionPrivacy.load(vocabulary),
                new JSONObject(data.toString(StandardCharsets.UTF_8.name())));
    }

    public JSONObject filter(JSONObject request) throws JSONException, Blocked, Incomplete {
        return filter(request,null);
    }
    public JSONObject filter(JSONObject request,String setting) throws JSONException, Blocked, Incomplete {
        return filter(request, setting, true);
    }
    public JSONObject filter(JSONObject request, String setting, boolean imageMaskReady) throws JSONException, Blocked, Incomplete {
        // Native text is filtered by identity, not by screenshot mask geometry.
        // Never permit an image path without its separate masking proof.
        if (request.optJSONObject("vision_snapshot") != null && !imageMaskReady)
            throw new Blocked(Blocked.Reason.VISION_MASK_UNAVAILABLE);
        JSONObject result = new JSONObject(request.toString());
        JSONObject tree = result.optJSONObject("accessibility_tree");
        JSONObject vision = result.optJSONObject("vision_snapshot");
        if (tree == null) {
            if (vision != null) throw new Blocked();
            return result;
        }
        JSONObject window=tree.optJSONObject("window_context");
        if(window!=null){
            java.util.Iterator<String> names=window.keys();
            while(names.hasNext())if(!Set.of("settings_activity","window_kind","keyboard_visible").contains(names.next()))throw new Blocked();
            Object activity=window.opt("settings_activity"),kind=window.opt("window_kind"),keyboard=window.opt("keyboard_visible");
            if(activity!=null && !(activity instanceof String) || kind!=null && !(kind instanceof String)
                    || keyboard!=null && !(keyboard instanceof Boolean))throw new Blocked();
            try{tree.put("window_context",new NativeWindowContext(window.optString("settings_activity","UNKNOWN"),
                    window.optString("window_kind","UNKNOWN"),window.optBoolean("keyboard_visible")).toJson());}
            catch(IllegalArgumentException invalid){throw new Blocked();}
        }
        String pkg = tree.optString("package_name");
        NativeControlSurface.Match surface=NativeControlSurface.identify(tree);
        boolean controlScope=surface!=null && (setting==null || setting.equals(surface.setting()));
        if (!settings.contains(pkg) && !launchers.contains(pkg) && !controlScope) throw new Blocked(Blocked.Reason.UNSUPPORTED_APP);
        JSONArray nodes = tree.getJSONArray("nodes");
        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            byId.put(node.getString("node_id"), node);
        }
        Set<String> hiddenRegions = privateNodes(byId);
        JSONArray safeNodes = new JSONArray();
        Set<String> needed = new HashSet<>();
        boolean recognized = controlScope;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (!node.optBoolean("visible_to_user", true)) continue;
            if (!pkg.equals(node.optString("package_name", pkg))) throw new Blocked(Blocked.Reason.UNSUPPORTED_APP);
            JSONObject extras = node.optJSONObject("extras");
            boolean sensitive = input(node, extras);
            boolean emptySearchField = emptySearch.contains(node.optString("resource_id"))
                    && node.optString("text", "").isEmpty() && !flag(extras, "input_content_present")
                    && !flag(extras, "password") && !flag(extras, "is_password") && !flag(extras, "isPassword");
            if (sensitive && !emptySearchField) throw new Blocked(Blocked.Reason.SENSITIVE_INPUT);
            if (hiddenRegions.contains(node.getString("node_id"))) continue;
            if (!description(node) && independent(node, byId) && (blockedTitles.contains(normalize(node.optString("text")))
                    || blockedTitles.contains(normalize(node.optString("content_description")))))
                throw new Blocked(Blocked.Reason.SENSITIVE_SCREEN);
            if(controlScope && !surface.nodeIds().contains(node.optString("node_id")))continue;
            if(otherSettingControl(node,byId,setting))continue;
            String resource = node.optString("resource_id");
            String resourceName = resource.substring(resource.lastIndexOf('/') + 1);
            boolean description = descriptions.contains(resourceName);
            for (String token : privateResources) description |= resourceName.toLowerCase(Locale.ROOT).contains(token);
            String text = sensitive || description ? null : approved(node.optString("text", null), pkg);
            String content = sensitive || description ? null : approved(node.optString("content_description", null), pkg);
            recognized |= text != null || content != null;
            if (description) continue;
            JSONObject safe = new JSONObject();
            for (String key : new String[]{"node_id", "window_id", "parent_id", "visible_to_user",
                    "bounds_in_screen", "selected", "scrollable", "checkable"})
                if (node.has(key)) safe.put(key, node.get(key));
            safe.put("package_name", pkg).put("text", text == null ? JSONObject.NULL : text)
                    .put("content_description", content == null ? JSONObject.NULL : content);
            if (resource.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_]+")) safe.put("resource_id", resource);
            String role = node.optString("role");
            if (roles.contains(role)) safe.put("role", role);
            boolean nativeCheckedControl = node.optBoolean("checkable") && extras != null
                    && extras.opt("checked") instanceof Boolean
                    && Set.of("switch", "toggle", "checkbox", "radio", "radio_button").contains(role);
            if (text != null || content != null || "slider".equals(role) || "list".equals(role) || nativeCheckedControl) {
                String ancestorId = node.getString("node_id");
                Set<String> seen = new HashSet<>();
                while (!ancestorId.isEmpty() && seen.add(ancestorId)) {
                    needed.add(ancestorId);
                    JSONObject ancestor = byId.get(ancestorId);
                    ancestorId = ancestor == null ? "" : ancestor.optString("parent_id", "");
                }
            }
            // Preserve a finite native class identity needed by server role inference.
            String className = node.optString("class_name");
            if (className.matches("android\\.widget\\.(TextView|Button|ImageButton|Switch|SwitchCompat|CheckBox|RadioButton|SeekBar|ListView|LinearLayout|FrameLayout|RelativeLayout)"
                    + "|android\\.view\\.View|androidx\\.recyclerview\\.widget\\.RecyclerView"))
                safe.put("class_name", className);
            else if ("slider".equals(role)) safe.put("class_name", "android.widget.SeekBar");
            else if ("switch".equals(role) || "toggle".equals(role)) safe.put("class_name", "android.widget.Switch");
            else if ("radio_button".equals(role) || "radio".equals(role)) safe.put("class_name", "android.widget.RadioButton");
            else if ("button".equals(role)) safe.put("class_name", "android.widget.Button");
            else if ("list".equals(role)) safe.put("class_name", "android.widget.ListView");
            safe.put("clickable", !sensitive && !description && !soundModeChoice(node,byId) && node.optBoolean("clickable"))
                    .put("enabled", !sensitive && !description && node.optBoolean("enabled", true));
            JSONObject numeric = new JSONObject();
            if (!sensitive && !description && extras != null) {
                for (String key : new String[]{"progress", "value", "current", "range_current", "percent", "min", "max"}) {
                    Object value = extras.opt(key);
                    if (value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                            && Math.abs(((Number) value).doubleValue()) <= 100_000_000) numeric.put(key, value);
                }
            }
            if (!sensitive && !description && node.optBoolean("checkable") && extras != null
                    && extras.opt("checked") instanceof Boolean) numeric.put("checked", extras.getBoolean("checked"));
            safe.put("extras", numeric);
            safeNodes.put(safe);
        }
        JSONArray relevantNodes = new JSONArray();
        for (int i = 0; i < safeNodes.length(); i++) {
            JSONObject node = safeNodes.getJSONObject(i);
            if (needed.contains(node.getString("node_id"))) relevantNodes.put(node);
        }
        if (vision != null) {
            // Recheck even locally generated OCR; never trust caller-provided free text.
            if (!pkg.equals(vision.optString("package_name")) || !vision.optBoolean("redaction_applied")) throw new Blocked();
            JSONArray elements = vision.optJSONArray("elements"), safeElements = new JSONArray();
            if (elements != null) for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                String text = approved(element.optString("text", null), pkg);
                if (text == null) continue;
                JSONObject safe = new JSONObject().put("element_id", "ocr_" + i).put("text", text);
                for (String key : new String[]{"bounds_in_screen", "confidence", "actionable"})
                    if (element.has(key)) safe.put(key, element.get(key));
                String role = element.optString("role");
                if (roles.contains(role)) safe.put("role", role);
                safeElements.put(safe);
            }
            recognized |= safeElements.length() > 0;
            JSONObject safeVision = new JSONObject();
            for (String key : new String[]{"user_consented", "redaction_applied", "observation_id", "screen_revision",
                    "active_window_id", "package_name", "screen_width", "screen_height", "captured_at_ms", "processing_time_ms"})
                if (vision.has(key)) safeVision.put(key, vision.get(key));
            safeVision.put("provider", AgentVisionSnapshot.PROVIDER).put("elements", safeElements);
            result.put("vision_snapshot", safeVision);
        }
        if (nodes.length() > 0 && !recognized) {
            // Settings can expose only its container/back button during redraw.
            // Keep every unrecognized label local and wait for a readable screen.
            if (settings.contains(pkg) && vision == null) throw new Incomplete();
            throw new Blocked();
        }
        JSONObject safeTree = new JSONObject();
        for (String key : new String[]{"package_name", "active_window_id", "captured_at", "observation_id",
                "screen_revision", "captured_at_ms", "screen_width", "screen_height", "window_context"})
            if (tree.has(key)) safeTree.put(key, tree.get(key));
        JSONObject applied = tree.optJSONObject("applied_settings");
        if (applied != null) {
            JSONObject safeApplied = new JSONObject();
            String[][] values = {{"font_scale", "source", "android.provider.Settings.System.FONT_SCALE"},
                    {"screen_brightness", "brightness_source", "android.provider.Settings.System.SCREEN_BRIGHTNESS"},
                    {"media_volume", "media_volume_source", "android.media.AudioManager.STREAM_MUSIC"}};
            for (String[] field : values) {
                Object value = applied.opt(field[0]);
                if (field[2].equals(applied.optString(field[1])) && value instanceof Number
                        && Double.isFinite(((Number) value).doubleValue())
                        && Math.abs(((Number) value).doubleValue()) <= 100_000_000)
                    safeApplied.put(field[0], value).put(field[1], field[2]);
            }
            Object wifiState = applied.opt("wifi_state");
            if ("android.net.wifi.WifiManager.getWifiState".equals(applied.optString("wifi_source"))
                    && wifiState instanceof Integer && ((Integer) wifiState) >= 0 && ((Integer) wifiState) <= 4)
                safeApplied.put("wifi_source", "android.net.wifi.WifiManager.getWifiState").put("wifi_state", wifiState);
            Object nightMode = applied.opt("night_mode");
            if ("dark_mode".equals(setting)
                    && "android.app.UiModeManager.getNightMode".equals(applied.optString("night_mode_source"))
                    && nightMode instanceof Integer && ((Integer) nightMode) >= 0 && ((Integer) nightMode) <= 3)
                safeApplied.put("night_mode_source", "android.app.UiModeManager.getNightMode").put("night_mode", nightMode);
            Object ringerMode = applied.opt("ringer_mode");
            if (("mute".equals(setting) || "vibrate".equals(setting))
                    && "android.media.AudioManager.getRingerMode".equals(applied.optString("ringer_mode_source"))
                    && ringerMode instanceof Integer && ((Integer) ringerMode) >= 0 && ((Integer) ringerMode) <= 2)
                safeApplied.put("ringer_mode_source", "android.media.AudioManager.getRingerMode").put("ringer_mode", ringerMode);
            Object density = applied.opt("display_density_dpi");
            if ("display_size".equals(setting)
                    && "android.content.res.Configuration.densityDpi".equals(applied.optString("display_density_source"))
                    && density instanceof Integer && ((Integer)density) >= 72 && ((Integer)density) <= 2000)
                safeApplied.put("display_density_source", "android.content.res.Configuration.densityDpi").put("display_density_dpi", density);
            if (safeApplied.length() > 0) safeTree.put("applied_settings", safeApplied);
        }
        safeTree.put("nodes", relevantNodes);
        result.put("accessibility_tree", safeTree);
        return result;
    }

    private boolean description(JSONObject node) {
        String resource = node.optString("resource_id", "");
        String name = resource.substring(resource.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (descriptions.contains(name)) return true;
        for (String token : privateResources) if (name.contains(token)) return true;
        return false;
    }

    private Set<String> privateNodes(Map<String, JSONObject> byId) throws JSONException {
        Set<String> roots = new HashSet<>();
        for (JSONObject node : byId.values()) {
            String resource = node.optString("resource_id", "").toLowerCase(Locale.ROOT);
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            boolean identity = false;
            for (String token : privateResources) identity |= name.contains(token);
            for (String token : privateRegions) identity |= name.contains(token);
            if (!identity) continue;
            roots.add(node.getString("node_id"));
            // A private identity can label its entire clickable row. Redact that
            // row too, so an SSID/device/account called "Settings" isn't a menu.
            Set<String> seen = new HashSet<>();
            JSONObject parent = byId.get(node.optString("parent_id"));
            while (parent != null && seen.add(parent.optString("node_id"))) {
                if (parent.optBoolean("scrollable")) break;
                if (parent.optBoolean("clickable")) {
                    if (parent.optString("parent_id", "").length() > 0)
                        roots.add(parent.getString("node_id"));
                    break;
                }
                parent = byId.get(parent.optString("parent_id"));
            }
        }
        Set<String> hidden = new HashSet<>();
        for (JSONObject node : byId.values()) {
            Set<String> seen = new HashSet<>();
            for (JSONObject current = node; current != null && seen.add(current.optString("node_id"));
                    current = byId.get(current.optString("parent_id"))) {
                if (roots.contains(current.optString("node_id"))) { hidden.add(node.getString("node_id")); break; }
            }
        }
        return hidden;
    }

    private String approved(String value, String pkg) {
        String text = vocabulary.exportSettingsLabel(value);
        return launchers.contains(pkg) && !"설정".equals(text) && !"settings".equals(text) ? null : text;
    }

    private static boolean otherSettingControl(JSONObject node,Map<String,JSONObject> byId,String setting) {
        if(setting==null || !Set.of("font_size","brightness","volume").contains(setting))return false;
        Set<String> seen=new HashSet<>();
        for(JSONObject current=node;current!=null && seen.add(current.optString("node_id"));
                current=byId.get(current.optString("parent_id"))) {
            String identity=SettingsControlResources.sliderSetting(current.optString("resource_id",null));
            if(identity!=null && !identity.equals(setting))return true;
        }
        return false;
    }

    private static boolean soundModeChoice(JSONObject node,Map<String,JSONObject> byId) {
        Set<String> seen=new HashSet<>();
        for(JSONObject current=node;current!=null && seen.add(current.optString("node_id"));
                current=byId.get(current.optString("parent_id"))) {
            if(SettingsControlResources.soundModeChoice(current.optString("resource_id",null)))return true;
        }
        return false;
    }

    private static boolean independent(JSONObject node, Map<String, JSONObject> byId) {
        if (node.optBoolean("clickable")) return false;
        Set<String> seen = new HashSet<>();
        String parent = node.optString("parent_id", "");
        while (!parent.isEmpty() && seen.add(parent)) {
            JSONObject ancestor = byId.get(parent);
            if (ancestor == null) break;
            if (ancestor.optBoolean("clickable") || ancestor.optBoolean("scrollable")) return false;
            parent = ancestor.optString("parent_id", "");
        }
        return true;
    }

    private static boolean input(JSONObject node, JSONObject extras) {
        String role = node.optString("role");
        String className = node.optString("class_name").toLowerCase(Locale.ROOT);
        return className.contains("edittext") || "text_field".equals(role) || "text_input".equals(role)
                || flag(extras, "password") || flag(extras, "is_password") || flag(extras, "isPassword")
                || flag(extras, "editable") || flag(extras, "isEditable");
    }

    private static boolean flag(JSONObject object, String key) { return object != null && object.optBoolean(key); }
    private static String normalize(String text) { return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private static Set<String> strings(JSONArray array) throws JSONException {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }
}
