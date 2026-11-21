package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.AppTaskExecutor;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in inventory of actual public app UI. Never reads input values or opens documents. */
@RunWith(AndroidJUnit4.class)
public final class MetaControllerPublicAppsExperimentTest {
    private static final String VERSION="click.native-public-apps.v1";
    private static final Set<String> PACKAGES=Set.of("com.android.settings","com.google.android.deskclock",
        "com.google.android.documentsui","com.android.chrome");
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private volatile String currentPackage="com.android.settings";

    @Test public void probePublicApps()throws Exception {
        Assume.assumeTrue("Opt in with publicAppsProbe",flag("publicAppsProbe"));isolated();JSONArray pages=new JSONArray();
        try(Session session=new Session()){
            for(String pkg:Arrays.asList("com.android.settings","com.google.android.deskclock","com.google.android.documentsui")){
                currentPackage=pkg;JSONObject page=new JSONObject().put("package",pkg).put("probe","home");
                try{home();page=probePage(session,"home");}catch(Exception failed){page.put("available",false).put("reason",failed instanceof ProbeFailure?failed.getMessage():failed.getClass().getSimpleName());}
                pages.put(page);
            }
        }
        JSONObject report=new JSONObject().put("schema_version",VERSION).put("source","actual_native_public_apps_probe")
            .put("model_calls",0).put("sdk",Build.VERSION.SDK_INT).put("locale",locale()).put("pages",pages);
        write("probe-homes.json",report.toString(2),false);System.out.println("PUBLIC_APPS_PROBE files/public-apps-meta-v1/probe-homes.json");
    }

    /** Capture a root-operated public destination without launching or resetting the app. */
    @Test public void probeCurrentPublicApp()throws Exception {
        Assume.assumeTrue("Opt in with publicAppsCurrentProbe",flag("publicAppsCurrentProbe"));isolated();
        String pkg=args.getString("probePackage","");assertTrue("Only isolated public app inventory",PACKAGES.contains(pkg));currentPackage=pkg;
        String name=args.getString("probeName","current");assertTrue(name.matches("[A-Za-z0-9_-]{1,64}"));
        try(Session session=new Session()){
            if(flag("probeResetHome"))home();
            JSONArray navigation=new JSONArray();String encoded=args.getString("probeNavigation64");
            if(encoded!=null){JSONArray steps=new JSONArray(new String(Base64.decode(encoded,Base64.DEFAULT),StandardCharsets.UTF_8));assertTrue(steps.length()<=6);
                for(int i=0;i<steps.length();i++)navigation.put(navigatePublicSelector(steps.getJSONObject(i)));}
            JSONObject report=probePage(session,name).put("schema_version",VERSION).put("source","actual_native_public_apps_probe")
                .put("model_calls",0).put("sdk",Build.VERSION.SDK_INT).put("locale",locale())
                .put("accessibility_kept_enabled_during_navigation",true).put("reset_home_inside_session",flag("probeResetHome")).put("navigation",navigation);
            write("probe-"+name+".json",report.toString(2),false);System.out.println("PUBLIC_APPS_CURRENT_PROBE "+name);
        }
    }

    /** Trusted experiment fixture selectors only; execute through the unmodified production native guard. */
    private JSONObject navigatePublicSelector(JSONObject selector)throws Exception {
        assertEquals(2,selector.length());assertTrue(selector.has("label")&&selector.has("resource"));String label=selector.getString("label"),resource=selector.getString("resource");
        assertFalse(label.isEmpty());assertEquals(label,AppTaskObservation.label(label));ScreenSnapshot before=stablePublicApp();
        JSONObject observed=AppTaskObservation.capture(before);assertEquals("public",observed.optString("status"));JSONObject target=null;
        JSONArray nodes=observed.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);
            if(label.equals(n.optString("label"))&&resource.equals(n.optString("resource"))&&Set.of("button","tab").contains(n.optString("role"))
                    &&n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable")&&n.isNull("checked")){
                assertNull("Only one exact current public navigation target",target);target=n;}}
        assertNotNull("Required navigation target must exist in production projection",target);
        JSONObject action=new JSONObject().put("id","model-free-probe-navigation").put("tool","tap").put("package",currentPackage)
            .put("target_id",target.getString("id")).put("bounds",target.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed"));
        boolean accepted=AppTaskExecutor.execute(service(),before,ClickAccessibilityService.freshSnapshot(),observed,action,null,currentPackage);
        assertTrue("Current native navigation must accept its exact production action",accepted);SystemClock.sleep(750);
        return new JSONObject().put("label",label).put("resource",resource).put("production_native_executor_accepted",accepted);
    }

    private JSONObject probePage(Session session,String name)throws Exception {
        ScreenSnapshot frame=stablePublicApp();JSONObject projected=AppTaskObservation.capture(frame);
        AccessibilityNodeInfo root=foregroundRoot(session.automation);if(root==null)throw new ProbeFailure("foreground_root_unavailable");
        try{
            if(!currentPackage.equals(String.valueOf(root.getPackageName())))throw new ProbeFailure("unexpected_foreground_package");
            String window=AppTaskObservation.hash(currentPackage+"|"+root.getWindowId()).substring(0,24);
            boolean protectedFrame="protected".equals(projected.optString("status"));
            JSONArray inventory=new JSONArray();boolean[] documentItems={false};
            inventory(root,inventory,"node_0",null,window,protectedFrame,false,documentItems);
            JSONObject result=new JSONObject().put("package",currentPackage).put("probe",name).put("available","public".equals(projected.optString("status"))&&!documentItems[0])
                .put("projection_status",projected.optString("status"))
                .put("protected_frame",protectedFrame).put("document_items_present",documentItems[0])
                .put("input_values_saved",false).put("document_entry_labels_saved",false).put("window_ref",window)
                .put("snapshot_active_window_ref",frame.activeWindowId==null?JSONObject.NULL:AppTaskObservation.hash(currentPackage+"|"+frame.activeWindowId).substring(0,24))
                .put("native_root_matches_snapshot_window",frame.activeWindowId!=null&&root.getWindowId()==frame.activeWindowId)
                .put("native_root_window",windowMetadata(root)).put("snapshot_node_count",frame.nodes.size())
                .put("width",frame.screenWidth).put("height",frame.screenHeight).put("observation_ref",AppTaskObservation.hash(frame.observationId).substring(0,24))
                .put("controls",inventory);
            if(!documentItems[0])result.put("controller_observation",projected);
            else result.put("reason","document_items_present_no_model_or_file_open");
            return result;
        }finally{root.recycle();}
    }

    private void inventory(AccessibilityNodeInfo node,JSONArray out,String path,String parent,String window,
            boolean protectedFrame,boolean inDocumentList,boolean[] documentItems)throws Exception {
        if(out.length()>=200)return;
        String resource=node.getViewIdResourceName();resource=resource==null?"":resource;
        boolean documents=currentPackage.equals("com.google.android.documentsui");
        boolean documentList=inDocumentList||documents&&resource.matches(".*:id/(?:dir_list|documents_list|document_list)(?:_.*)?");
        boolean visible=node.isVisibleToUser(),same=currentPackage.equals(String.valueOf(node.getPackageName()));
        boolean input=node.isEditable()||node.isPassword();
        boolean redact=protectedFrame||input||documentList;
        if(visible&&same){
            if(documentList&&resource.matches(".*:id/(?:title|display_name|document_name|filename)")
                    &&node.getText()!=null&&node.getText().length()>0)documentItems[0]=true;
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            String label=redact?"":publicLabel(node.getContentDescription());if(!redact&&label.isEmpty())label=publicLabel(node.getText());
            String cls=String.valueOf(node.getClassName()),lower=cls.toLowerCase(java.util.Locale.ROOT);
            String role=input?"text_field":node.isCheckable()?"toggle":lower.contains("tab")||resource.toLowerCase(java.util.Locale.ROOT).contains("tab_")?"tab":node.isScrollable()?"list":node.isClickable()?"button":node.isHeading()?"text":"unknown";
            out.put(new JSONObject().put("path",path).put("parent_path",parent==null?JSONObject.NULL:parent)
                .put("scoped_path",currentPackage+"|"+window+"|"+path).put("label",label).put("label_redacted",redact)
                .put("resource",resource).put("class",cls).put("role",role).put("visible",visible).put("enabled",node.isEnabled())
                .put("clickable",node.isClickable()).put("editable",node.isEditable()).put("password",node.isPassword())
                .put("scrollable",node.isScrollable()).put("checkable",node.isCheckable()).put("selected",node.isSelected())
                .put("checked",node.isCheckable()?node.isChecked():JSONObject.NULL).put("heading",node.isHeading())
                .put("native_action_ids",actionIds(node)).put("native_click_action",hasAction(node,AccessibilityNodeInfo.ACTION_CLICK))
                .put("native_select_action",hasAction(node,AccessibilityNodeInfo.ACTION_SELECT)).put("collection_item",collectionItem(node))
                .put("ancestor_capabilities",ancestorCapabilities(node))
                .put("document_list_descendant",documentList).put("bounds",new JSONArray(new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom})));
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{inventory(child,out,path+"_"+i,path,window,protectedFrame,documentList,documentItems);}finally{child.recycle();}}
    }

    private static JSONArray actionIds(AccessibilityNodeInfo node){JSONArray ids=new JSONArray();for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())ids.put(action.getId());return ids;}
    private static Object windowMetadata(AccessibilityNodeInfo root)throws Exception {AccessibilityWindowInfo window=root.getWindow();if(window==null)return JSONObject.NULL;
        try{return new JSONObject().put("type",window.getType()).put("layer",window.getLayer()).put("active",window.isActive()).put("focused",window.isFocused()).put("picture_in_picture",window.isInPictureInPictureMode());}
        finally{window.recycle();}}
    private static boolean hasAction(AccessibilityNodeInfo node,int id){for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())if(action.getId()==id)return true;return false;}
    private static Object collectionItem(AccessibilityNodeInfo node)throws Exception {AccessibilityNodeInfo.CollectionItemInfo item=node.getCollectionItemInfo();
        return item==null?JSONObject.NULL:new JSONObject().put("row_index",item.getRowIndex()).put("column_index",item.getColumnIndex())
            .put("row_span",item.getRowSpan()).put("column_span",item.getColumnSpan()).put("selected",item.isSelected()).put("heading",item.isHeading());}
    private static JSONArray ancestorCapabilities(AccessibilityNodeInfo node)throws Exception {JSONArray parents=new JSONArray();AccessibilityNodeInfo parent=node.getParent();int depth=0;
        while(parent!=null&&depth++<12){AccessibilityNodeInfo next=null;try{
            parents.put(new JSONObject().put("class",String.valueOf(parent.getClassName())).put("visible",parent.isVisibleToUser()).put("enabled",parent.isEnabled())
                .put("clickable",parent.isClickable()).put("checkable",parent.isCheckable()).put("native_action_ids",actionIds(parent))
                .put("native_click_action",hasAction(parent,AccessibilityNodeInfo.ACTION_CLICK)).put("native_select_action",hasAction(parent,AccessibilityNodeInfo.ACTION_SELECT)));
            next=parent.getParent();}finally{parent.recycle();}parent=next;}
        if(parent!=null)parent.recycle();return parents;
    }

    private void home()throws Exception {
        assertTrue(PACKAGES.contains(currentPackage));shell("am force-stop "+currentPackage);
        Intent intent=currentPackage.equals("com.android.settings")?new Intent(Settings.ACTION_SETTINGS).setPackage(currentPackage)
            :app.getPackageManager().getLaunchIntentForPackage(currentPackage);
        if(intent==null)throw new ProbeFailure("launcher_unavailable");
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));stablePublicApp();
    }
    private ScreenSnapshot stablePublicApp()throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000,since=-1;String last=null;
        while(SystemClock.elapsedRealtime()<deadline){ScreenSnapshot frame=ClickAccessibilityService.freshSnapshot();
            if(frame!=null&&currentPackage.equals(frame.packageName)){
                JSONObject observed=AppTaskObservation.capture(frame);String key=observed.optString("fingerprint");
                if(key.equals(last)){if(since>=0&&SystemClock.elapsedRealtime()-since>=600)return frame;}
                else{last=key;since=SystemClock.elapsedRealtime();}
            }else{last=null;since=-1;}SystemClock.sleep(150);
        }throw new ProbeFailure("public_app_window_not_stable");
    }
    private static AccessibilityNodeInfo foregroundRoot(UiAutomation automation){
        List<AccessibilityWindowInfo> windows=automation.getWindows();if(windows==null)return null;
        AccessibilityNodeInfo chosen=null;int layer=Integer.MIN_VALUE;
        try{for(AccessibilityWindowInfo window:windows){
            if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD||!window.isActive()&&!window.isFocused())continue;
            AccessibilityNodeInfo root=window.getRoot();String own=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
            if(window.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&root!=null&&own.equals(String.valueOf(root.getPackageName()))){root.recycle();continue;}
            if(window.getLayer()>layer){if(chosen!=null)chosen.recycle();chosen=root;layer=window.getLayer();}else if(root!=null)root.recycle();
        }return chosen;}finally{for(AccessibilityWindowInfo window:windows)window.recycle();}
    }
    private final class Session implements AutoCloseable {
        final UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final String original;final int enabled,flags,eventTypes;
        Session()throws Exception {
            original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
            AccessibilityServiceInfo info=automation.getServiceInfo();flags=info.flags;eventTypes=info.eventTypes;
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            try{
                Set<String> services=new LinkedHashSet<>();if(original!=null)for(String item:original.split(":"))if(!item.isEmpty())services.add(item);
                ComponentName own=new ComponentName(app,ClickAccessibilityService.class);services.remove(own.flattenToString());
                Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                SystemClock.sleep(350);services.add(own.flattenToString());
                Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
                long end=SystemClock.elapsedRealtime()+10000;while(service()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
                assertNotNull("Accessibility service must connect",service());
            }catch(Exception|AssertionError failed){close();throw failed;}
        }
        public void close(){
            try{Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
                Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);
                AccessibilityServiceInfo info=automation.getServiceInfo();info.flags=flags;info.eventTypes=eventTypes;automation.setServiceInfo(info);
            }finally{automation.dropShellPermissionIdentity();}
        }
    }
    private void isolated(){assertEquals("Use only the isolated public-apps test build","com.click.metaexperiment.publicapps",app.getPackageName());assertEquals("Fresh emulator only","ranchu",Build.HARDWARE);}
    private static ClickAccessibilityService service(){try{var field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);return (ClickAccessibilityService)((WeakReference<?>)field.get(null)).get();}catch(Exception error){throw new AssertionError(error);}}
    private static String locale(){return android.content.res.Resources.getSystem().getConfiguration().getLocales().toLanguageTags();}
    private static String publicLabel(CharSequence value){return AppTaskObservation.label(value==null?"":value.toString());}
    private void shell(String command)throws Exception {try(var fd=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);var in=new java.io.FileInputStream(fd.getFileDescriptor())){while(in.read()!=-1){}}}
    private boolean flag(String name){return Boolean.parseBoolean(args.getString(name,"false"));}
    private File directory(){File directory=new File(app.getFilesDir(),"public-apps-meta-v1");directory.mkdirs();return directory;}
    private void write(String name,String value,boolean append)throws Exception {try(FileOutputStream out=new FileOutputStream(new File(directory(),name),append)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static final class ProbeFailure extends Exception {ProbeFailure(String reason){super(reason);}}
}
