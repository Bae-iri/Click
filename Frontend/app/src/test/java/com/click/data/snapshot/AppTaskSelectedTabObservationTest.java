package com.click.data.snapshot;

import com.click.service.overlay.AppTaskCompletion;
import com.click.service.overlay.AppTaskPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

/** Read-only selected tab state is evidence, never newly executable authority. */
public class AppTaskSelectedTabObservationTest {
    private static final String PKG="org.example.utility",LABEL="History";
    private static AccessibilityNodeSnapshot tab(String id,String parent,boolean visible,boolean enabled,boolean selected,int window,String cls,String resource){
        return new AccessibilityNodeSnapshot(id,parent,visible,PKG,LABEL,null,resource,cls,null,
            new int[]{10,20,500,120},false,enabled,selected,false,false,null,window,false,null,null,false,null,false,false);
    }
    private static JSONObject proof()throws Exception {
        return new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",PKG).put("label",LABEL)
            .put("resource",PKG+":id/tab_menu_history").put("role","tab").put("selected",true)));
    }
    @Test public void selectedNonclickableNativeTabIsExportedWithoutPredictedResultLabels()throws Exception {
        AccessibilityNodeSnapshot tab=tab("selected",null,true,true,true,42,"android.widget.TextView",PKG+":id/tab_menu_history");
        ScreenSnapshot nativeScreen=screen(PKG,tab);JSONObject observed=AppTaskObservation.capture(nativeScreen),node=AppTaskObservation.find(observed,"selected");
        assertNotNull(node);assertEquals("public",observed.getString("status"));assertEquals("tab",node.getString("role"));assertTrue(node.getBoolean("selected"));
        assertFalse(node.getBoolean("clickable"));assertFalse(node.getBoolean("scrollable"));assertFalse(node.getBoolean("editable"));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("Open History tab",proof(),observed,nativeScreen));
    }
    @Test public void disabledSelectedNativeTabRemainsReadOnlyEvidence()throws Exception {
        ScreenSnapshot nativeScreen=screen(PKG,tab("selected",null,true,false,true,42,"android.widget.TabView",PKG+":id/tab_menu_history"));
        JSONObject observed=AppTaskObservation.capture(nativeScreen),node=AppTaskObservation.find(observed,"selected");assertNotNull(node);
        for(String flag:List.of("clickable","editable","scrollable","has_value"))assertFalse(flag,node.getBoolean(flag));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("Open History tab",proof(),observed,nativeScreen));
        for(String tool:List.of("tap","scroll_forward","set_text")){
            JSONObject action=new JSONObject().put("tool",tool).put("package",PKG).put("target_id","selected").put("bounds",node.getJSONArray("bounds"))
                .put("expectation",new JSONObject().put("kind",tool.equals("set_text")?"input_present":"screen_changed"));
            assertFalse(tool,AppTaskPolicy.allowed(observed,observed,action,PKG));
        }
    }
    @Test public void passiveUnselectedTabsAndSelectedBodyTextOrTabContainersStayExcluded()throws Exception {
        ScreenSnapshot nativeScreen=screen(PKG,
            tab("unselected",null,true,true,false,42,"android.widget.TextView",PKG+":id/tab_menu_history"),
            tab("disabled_unselected",null,true,false,false,42,"android.widget.TabView",PKG+":id/tab_menu_other"),
            tab("body",null,true,true,true,42,"android.widget.TextView",PKG+":id/selected_body"),
            tab("container",null,true,true,true,42,"com.google.android.material.tabs.TabLayout",PKG+":id/navigation"),
            tab("table",null,true,true,true,42,"android.widget.TableRow",PKG+":id/item"),
            node(PKG,"home","Home","button"));
        JSONObject observed=AppTaskObservation.capture(nativeScreen);assertNotNull(AppTaskObservation.find(observed,"home"));
        for(String id:List.of("unselected","disabled_unselected","body","container","table"))assertNull(id,AppTaskObservation.find(observed,id));
    }
    @Test public void selectedTabAdmissionRetainsVisibilityPrivacyAndNativeWindowBoundaries()throws Exception {
        AccessibilityNodeSnapshot hiddenParent=new AccessibilityNodeSnapshot("private_parent",null,true,PKG,null,null,PKG+":id/password_container",
            "android.widget.FrameLayout",null,new int[]{0,0,600,300},false,true,false,false,false,null,42);
        ScreenSnapshot nativeScreen=screen(PKG,hiddenParent,
            tab("hidden_child","private_parent",true,true,true,42,"android.widget.TabView",PKG+":id/tab_menu_hidden"),
            tab("invisible",null,false,true,true,42,"android.widget.TabView",PKG+":id/tab_menu_invisible"),
            tab("other_window",null,true,true,true,43,"android.widget.TabView",PKG+":id/tab_menu_other"),
            node(PKG,"home","Home","button"));
        JSONObject observed=AppTaskObservation.capture(nativeScreen);
        for(String id:List.of("hidden_child","invisible","other_window"))assertNull(id,AppTaskObservation.find(observed,id));
        ScreenSnapshot privateScreen=screen(PKG,tab("selected",null,true,true,true,42,"android.widget.TabView",PKG+":id/tab_menu_history"),node(PKG,"private","PRIVATE_PASSWORD","password"));
        JSONObject protectedObservation=AppTaskObservation.capture(privateScreen);assertEquals("protected",protectedObservation.getString("status"));assertEquals(0,protectedObservation.getJSONArray("nodes").length());
    }
    @Test public void selectedTabAdmissionRetainsBoundsAndPackageFiltering()throws Exception {
        AccessibilityNodeSnapshot outside=new AccessibilityNodeSnapshot("outside",null,true,PKG,LABEL,null,PKG+":id/tab_menu_outside","android.widget.TabView",null,
            new int[]{-10,20,500,120},false,true,true,false,false,null,42);
        AccessibilityNodeSnapshot foreign=new AccessibilityNodeSnapshot("foreign",null,true,"org.other.app",LABEL,null,"org.other.app:id/tab_menu_foreign","android.widget.TabView",null,
            new int[]{10,20,500,120},false,true,true,false,false,null,42);
        JSONObject observed=AppTaskObservation.capture(new ScreenSnapshot(PKG,Instant.now().toString(),1080,2400,List.of(outside,foreign,node(PKG,"home","Home","button")),42));
        assertNull(AppTaskObservation.find(observed,"outside"));assertNull(AppTaskObservation.find(observed,"foreign"));assertNotNull(AppTaskObservation.find(observed,"home"));
    }
}
