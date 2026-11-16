package com.click.data.snapshot;

import org.json.*;

/** Only finite window evidence is serialized; never arbitrary activity names or window titles. */
public record NativeWindowContext(String settingsActivity, String windowKind, boolean keyboardVisible) {
    public NativeWindowContext {
        if(!java.util.Set.of("UNKNOWN","HOME","SUBPAGE").contains(settingsActivity)
                || !java.util.Set.of("UNKNOWN","APPLICATION","INPUT_METHOD","SYSTEM","OVERLAY","DIALOG").contains(windowKind))
            throw new IllegalArgumentException("Only finite native window evidence is allowed");
    }
    public static final NativeWindowContext UNKNOWN=new NativeWindowContext("UNKNOWN","UNKNOWN",false);
    public JSONObject toJson(){try{return new JSONObject().put("settings_activity",settingsActivity).put("window_kind",windowKind).put("keyboard_visible",keyboardVisible);}catch(JSONException e){throw new IllegalStateException(e);}}
}
