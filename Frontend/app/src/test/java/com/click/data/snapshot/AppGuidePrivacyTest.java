package com.click.data.snapshot;

import com.click.data.remote.AppGuideGateway;
import org.json.*;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public class AppGuidePrivacyTest {
    private AccessibilityNodeSnapshot node(String pkg,String id,String text,boolean sensitive,boolean password) {
        return new AccessibilityNodeSnapshot(id,null,true,pkg,text,null,pkg+":id/"+id,
            sensitive?"android.widget.EditText":"android.widget.Button",null,new int[]{10,20,400,120},
            true,true,false,false,false,null,42,sensitive,null,null,password);
    }
    private ScreenSnapshot screen(String pkg,AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,List.of(nodes),42);
    }
    @Test public void arbitraryAppPackagesCanExportVisibleControls() throws Exception {
        for(String pkg:List.of("com.google.android.youtube","com.spotify.music","org.mozilla.firefox","com.example.newapp")) {
            JSONObject safe=new AppGuidePrivacy().filter(screen(pkg,node(pkg,"search","검색",false,false)));
            assertEquals("public",safe.getString("status"));
            assertEquals("검색",safe.getJSONArray("nodes").getJSONObject(0).getString("label"));
        }
    }
    @Test public void credentialScreenExportsNoControlsOrInput() throws Exception {
        String pkg="com.example.newapp";
        JSONObject safe=new AppGuidePrivacy().filter(screen(pkg,node(pkg,"password","PRIVATE_PASSWORD",true,true),node(pkg,"login","로그인",false,false)));
        assertEquals("sensitive",safe.getString("status"));
        assertEquals(0,safe.getJSONArray("nodes").length());assertFalse(safe.toString().contains("PRIVATE_PASSWORD"));
    }
    @Test public void personalContactLabelsAreRemovedButPublicControlsRemain() throws Exception {
        String pkg="com.example.newapp";
        JSONObject safe=new AppGuidePrivacy().filter(screen(pkg,node(pkg,"sender","홍길동",false,false),node(pkg,"title","010-1234-5678",false,false),node(pkg,"search","검색",false,false)));
        assertEquals(1,safe.getJSONArray("nodes").length());assertFalse(safe.toString().contains("홍길동"));assertFalse(safe.toString().contains("010-1234"));
    }
    @Test public void responseCannotHighlightInventedOrOldScreenCoordinates() throws Exception {
        String pkg="com.example.newapp";
        JSONObject safe=new AppGuidePrivacy().filter(screen(pkg,node(pkg,"search","검색",false,false)));
        JSONObject request=new JSONObject().put("request_id","r").put("screen",safe);
        JSONObject response=new JSONObject().put("protocol_version","app-guide.v1").put("request_id","r").put("manual_only",true)
            .put("observation_id",safe.getString("observation_id")).put("status","guide")
            .put("target_node_id","search").put("target_bounds",new JSONArray(new int[]{10,20,400,120}));
        AppGuideGateway.validate(request,response);
        response.put("target_bounds",new JSONArray(new int[]{20,20,400,120}));
        try{AppGuideGateway.validate(request,response);fail("Unsubmitted coordinates accepted");}catch(java.io.IOException expected){}
        response.put("target_bounds",new JSONArray(new int[]{10,20,400,120})).put("observation_id","old");
        try{AppGuideGateway.validate(request,response);fail("Old observation accepted");}catch(java.io.IOException expected){}
    }
}
