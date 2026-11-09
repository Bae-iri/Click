package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

public final class ScreenSnapshot {
    public final NavigationState navigation;
    public final String packageName;
    public final Integer activeWindowId;
    public final String capturedAt;
    public final String observationId = java.util.UUID.randomUUID().toString();
    public final int screenWidth;
    public final int screenHeight;
    public final List<AccessibilityNodeSnapshot> nodes;
    public final boolean privacyMaskingReady;
    public final Float appliedFontScale;
    public final Integer appliedBrightness, appliedMediaVolume;
    public final Integer appliedWifiState;
    public final String screenRevision;

    public ScreenSnapshot(
            String packageName,
            String capturedAt,
            int screenWidth,
            int screenHeight,
            List<AccessibilityNodeSnapshot> nodes
    ) {
        this(packageName, capturedAt, screenWidth, screenHeight, nodes, null);
    }

    public ScreenSnapshot(String packageName, String capturedAt, int screenWidth, int screenHeight,
                          List<AccessibilityNodeSnapshot> nodes, Integer activeWindowId) {
        this(packageName, capturedAt, screenWidth, screenHeight, nodes, activeWindowId, true);
    }

    public ScreenSnapshot(String packageName, String capturedAt, int screenWidth, int screenHeight,
                          List<AccessibilityNodeSnapshot> nodes, Integer activeWindowId, boolean privacyMaskingReady) {
        this(packageName, capturedAt, screenWidth, screenHeight, nodes, activeWindowId, privacyMaskingReady, null);
    }

    public ScreenSnapshot(String packageName, String capturedAt, int screenWidth, int screenHeight,
                          List<AccessibilityNodeSnapshot> nodes, Integer activeWindowId, boolean privacyMaskingReady,
                          Float appliedFontScale) {
        this(packageName, capturedAt, screenWidth, screenHeight, nodes, activeWindowId,
                privacyMaskingReady, appliedFontScale, null, null, null, null, null);
    }

    private ScreenSnapshot(String packageName, String capturedAt, int screenWidth, int screenHeight,
                          List<AccessibilityNodeSnapshot> nodes, Integer activeWindowId, boolean privacyMaskingReady,
                          Float appliedFontScale, String screenRevision, Integer brightness, Integer mediaVolume, Integer wifiState, NavigationState navigation) {
        this.navigation = navigation;
        this.appliedWifiState = wifiState;
        this.screenRevision = screenRevision;
        this.appliedFontScale = appliedFontScale;
        this.appliedBrightness = brightness;
        this.appliedMediaVolume = mediaVolume;
        this.privacyMaskingReady = privacyMaskingReady;
        this.activeWindowId = activeWindowId;
        this.packageName = packageName;
        this.capturedAt = capturedAt;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.nodes = Collections.unmodifiableList(nodes);
    }

    public ScreenSnapshot withScreenRevision(String revision) {
        return new ScreenSnapshot(packageName, capturedAt, screenWidth, screenHeight, nodes,
                activeWindowId, privacyMaskingReady, appliedFontScale, revision, appliedBrightness, appliedMediaVolume, appliedWifiState, navigation);
    }

    public ScreenSnapshot withAdjustmentValues(Integer brightness, Integer mediaVolume) {
        return new ScreenSnapshot(packageName, capturedAt, screenWidth, screenHeight, nodes,
                activeWindowId, privacyMaskingReady, appliedFontScale, screenRevision, brightness, mediaVolume, appliedWifiState, navigation);
    }

    public ScreenSnapshot withWifiState(Integer state) {
        return new ScreenSnapshot(packageName, capturedAt, screenWidth, screenHeight, nodes,
                activeWindowId, privacyMaskingReady, appliedFontScale, screenRevision,
                appliedBrightness, appliedMediaVolume, state, navigation);
    }

    public ScreenSnapshot withNavigation(NavigationState navigation) {
        return new ScreenSnapshot(packageName, capturedAt, screenWidth, screenHeight, nodes,
                activeWindowId, privacyMaskingReady, appliedFontScale, screenRevision,
                appliedBrightness, appliedMediaVolume, appliedWifiState, navigation);
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("package_name", packageName);
            if (activeWindowId != null) object.put("active_window_id", activeWindowId);
            object.put("captured_at", capturedAt);
            object.put("observation_id", observationId);
            if (screenRevision != null) object.put("screen_revision", screenRevision);
            object.put("captured_at_ms", java.time.Instant.parse(capturedAt).toEpochMilli());
            JSONObject applied = new JSONObject();
            if (appliedFontScale != null && Float.isFinite(appliedFontScale) && appliedFontScale > 0)
                applied.put("source", "android.provider.Settings.System.FONT_SCALE").put("font_scale", appliedFontScale);
            if (appliedBrightness != null && appliedBrightness >= 0 && appliedBrightness <= 65535)
                applied.put("brightness_source", "android.provider.Settings.System.SCREEN_BRIGHTNESS")
                        .put("screen_brightness", appliedBrightness);
            if (appliedMediaVolume != null && appliedMediaVolume >= 0 && appliedMediaVolume <= 1000)
                applied.put("media_volume_source", "android.media.AudioManager.STREAM_MUSIC")
                        .put("media_volume", appliedMediaVolume);
            if (appliedWifiState != null && appliedWifiState >= 0 && appliedWifiState <= 4)
                applied.put("wifi_source", "android.net.wifi.WifiManager.getWifiState")
                        .put("wifi_state", appliedWifiState);
            if (applied.length() > 0) object.put("applied_settings", applied);
            object.put("screen_width", screenWidth);
            object.put("screen_height", screenHeight);
            JSONArray nodeArray = new JSONArray();
            for (AccessibilityNodeSnapshot node : nodes) nodeArray.put(node.toJson());
            object.put("nodes", nodeArray);
            return object.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("ScreenSnapshot JSON 생성 실패", error);
        }
    }

    public String fingerprint() {
        return fingerprint(true);
    }

    /** Native AX content can stay identical across redundant accessibility events. */
    public String contentFingerprint() {
        return fingerprint(false);
    }

    /** Empty trees still need event revisions to trigger a fresh observation. */
    public String observationFingerprint() {
        return nodes.isEmpty() ? fingerprint() : contentFingerprint();
    }

    /** Exact native payload comparison for answering a server-bound confirmation. */
    public boolean sameNativeContent(ScreenSnapshot other) {
        if (other == null || nodes.isEmpty() || other.nodes.isEmpty()
                || privacyMaskingReady != other.privacyMaskingReady) return false;
        return nativeContentJson().equals(other.nativeContentJson());
    }

    private String nativeContentJson() {
        try {
            JSONObject content = new JSONObject(toJson());
            for (String key : new String[]{"captured_at", "captured_at_ms", "observation_id", "screen_revision"})
                content.remove(key);
            return content.toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private String fingerprint(boolean includeRevision) {
        StringBuilder value = new StringBuilder(packageName).append('|').append(screenWidth)
                .append('x').append(screenHeight).append('|').append(activeWindowId);
        value.append('|').append(privacyMaskingReady).append('|').append(appliedFontScale);
        value.append('|').append(appliedBrightness).append('|').append(appliedMediaVolume).append('|').append(appliedWifiState);
        if (includeRevision) value.append('|').append(screenRevision);
        for (AccessibilityNodeSnapshot node : nodes) {
            value.append('|').append(node.nodeId).append(':').append(node.text)
                    .append(':').append(node.resourceId).append(':').append(node.packageName)
                    .append(':').append(node.windowId).append(':').append(node.role)
                    .append(':').append(node.sensitiveInput).append(':').append(node.rangeMin).append(':').append(node.rangeMax)
                    .append(':').append(node.contentDescription).append(':').append(node.className)
                    .append(':').append(java.util.Arrays.toString(node.bounds))
                    .append(':').append(node.parentId).append(':').append(node.visibleToUser)
                    .append(':').append(node.clickable).append(':').append(node.enabled)
                    .append(':').append(node.scrollable).append(':').append(node.checkable)
                    .append(':').append(node.selected).append(':').append(node.progress).append(':').append(node.checked);
        }
        return Integer.toHexString(value.toString().hashCode());
    }
}
