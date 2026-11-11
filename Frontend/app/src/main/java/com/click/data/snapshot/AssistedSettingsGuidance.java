package com.click.data.snapshot;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bundled task purpose and next steps. Screen data never supplies these instructions. */
public final class AssistedSettingsGuidance {
    private final JSONObject goals;
    public AssistedSettingsGuidance(JSONObject document) throws org.json.JSONException {
        if(!"settings-manual-guidance.v1".equals(document.getString("schema_version")))
            throw new org.json.JSONException("Unsupported manual guidance");
        goals=document.getJSONObject("goals");
    }
    public static AssistedSettingsGuidance load(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        byte[] buffer=new byte[4096];int count;
        while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
        return new AssistedSettingsGuidance(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
    }
    public boolean supports(String goal) { return goal!=null && goals.optJSONObject(goal)!=null; }
    public String privacyMessage(String goal,String base) {
        JSONObject record=goal==null?null:goals.optJSONObject(goal);
        if(record==null)return base;
        return record.optString("intent")+" "+base+" "+record.optString("manual");
    }
}
