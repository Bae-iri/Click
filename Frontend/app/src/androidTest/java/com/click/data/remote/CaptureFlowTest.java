package com.click.data.remote;

import android.app.UiAutomation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.*;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import com.click.testutil.ServiceStateAccess;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Presentation screenshots of the real product flow: request review, every drawn
 * "next tap" guide, approvals and the result check. The test taps the highlighted
 * target like a user would. Opt-in: -e captureFlows true [-e captureScenario key].
 */
public class CaptureFlowTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context app=inst.getTargetContext();
    private UiAutomation ui;
    private static final String[][] SCENARIOS={
        // key, request, start (settings|home|package)
        {"1_settings_change","미디어 음량 줄여줘","settings"},
        {"2_settings_open","와이파이 설정 열어줘","settings"},
        {"3_gov24","정부24에서 주민등록등본 발급 화면 찾아줘","kr.go.minwon.m"},
        {"4_coupang","쿠팡 장바구니 보여줘","com.coupang.mobile"},
        {"9_youtube","YouTube에서 구독 탭으로 이동해줘","home_request"},
        {"10_menu_features","","menu"},
        {"15_open_app","카메라 열어줘","open_app"},
        {"16_negative_feedback","야 이상한 색기야","feedback"},
        {"17_feedback_sent","야 이 멍청아","feedback_send"},
        {"18_text_size","","text_size"},
        {"11_signup","","signup"},{"12_login","","login"},{"13_text_input","와이파이 설정 열어줘","text"},{"14_voice_input","","voice"},
        {"5_other_app","시계에서 스톱워치 탭으로 이동해줘","com.sec.android.app.clockpackage"},
        {"6_multi_request","와이파이 설정 열고 블루투스 설정 열어줘","settings"},
        {"7_clarification","글자 크기 키워줘","settings"},
        {"8_home_menu","","app"},
    };
    private Object get(Object target,String name){return ServiceStateAccess.get(target,name);}
    private void call(Object target,String name,Class<?>[] types,Object... values){try{
        var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,values);
    }catch(Exception e){throw new AssertionError(e);}}
    private ClickOverlayService service()throws Exception{
        var f=ClickOverlayService.class.getDeclaredField("runningService");f.setAccessible(true);
        return (ClickOverlayService)((java.lang.ref.WeakReference<?>)f.get(null)).get();
    }
    private void shell(String command)throws Exception{
        try(var fd=ui.executeShellCommand(command);var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
    }
    private int shot=0;
    private android.os.Bundle args(){return InstrumentationRegistry.getArguments();}
    private List<android.view.accessibility.AccessibilityNodeInfo> editables(){
        var out=new ArrayList<android.view.accessibility.AccessibilityNodeInfo>();var r=ui.getRootInActiveWindow();if(r==null)return out;
        var queue=new ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();queue.add(r);
        while(!queue.isEmpty()){var n=queue.poll();if(n.isEditable()&&n.isVisibleToUser())out.add(n);
            for(int i=0;i<n.getChildCount();i++){var c=n.getChild(i);if(c!=null)queue.add(c);}}
        return out;
    }
    private void type(int index,String text){
        var fields=editables();if(index>=fields.size())return;var b=new android.os.Bundle();
        b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
        fields.get(index).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
        fields.get(index).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,b);SystemClock.sleep(500);
    }
    private void open(Class<?> activity){
        app.startActivity(new Intent(app,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));SystemClock.sleep(2200);
    }
    /** Account screens never submit a signup; login uses a made-up number so it fails. */
    private void accountOrInput(File dir,String[] s)throws Exception{
        switch(s[2]){
            case "signup" -> {
                open(com.click.designpreview.AccountStartActivity.class);capture(dir,"start");
                open(com.click.designpreview.SignupActivity.class);capture(dir,"signup_step1");
                type(0,args().getString("signupPhone","01012345678"));type(1,"1234");type(2,"1234");SystemClock.sleep(600);capture(dir,"signup_step1_filled");
                tapLabel("다음");SystemClock.sleep(1800);capture(dir,"signup_step2");
                // Next step after confirming the number; the final signup button is never pressed.
                tapLabel("이 전화번호로 계속");SystemClock.sleep(2000);capture(dir,"signup_step3");
                // Remaining steps: only "다음" is pressed, never the final signup/complete button.
                type(0,"김클릭");capture(dir,"signup_name_filled");
                for(int i=0;i<3;i++){if(!tapLabel("다음"))break;SystemClock.sleep(1800);capture(dir,"signup_next");}
            }
            case "login" -> {
                open(com.click.designpreview.AccountStartActivity.class);capture(dir,"start");
                open(com.click.designpreview.LoginActivity.class);capture(dir,"login_empty");
                type(0,"01000000000");type(1,"0000");SystemClock.sleep(600);capture(dir,"login_filled");
                tapLabel("로그인");SystemClock.sleep(3000);capture(dir,"login_result");
            }
            case "text" -> {
                open(com.click.HomeActivity.class);capture(dir,"home");
                var f=editables();if(!f.isEmpty()){android.graphics.Rect b=new android.graphics.Rect();f.get(0).getBoundsInScreen(b);shell("input tap "+b.centerX()+" "+b.centerY());}
                SystemClock.sleep(1200);capture(dir,"keyboard");
                type(0,s[1]);capture(dir,"typed");
                shell("input keyevent KEYCODE_ENTER");SystemClock.sleep(4000);capture(dir,"sent");
            }
            case "voice" -> {
                shell("pm grant "+app.getPackageName()+" android.permission.RECORD_AUDIO");
                open(com.click.HomeActivity.class);capture(dir,"home");
                tapLabel("말로 입력");
                var r=ui.getRootInActiveWindow();
                if(r!=null){var mic=r.findAccessibilityNodeInfosByViewId(app.getPackageName()+":id/home_microphone");
                    if(!mic.isEmpty()){android.graphics.Rect b=new android.graphics.Rect();mic.get(0).getBoundsInScreen(b);shell("input tap "+b.centerX()+" "+b.centerY());}}
                SystemClock.sleep(1500);capture(dir,"voice_panel");
                // Press and hold the panel microphone; the host speaks the request meanwhile.
                // The host presses and holds the microphone and speaks at the same moment.
                if(!"host".equals(args().getString("voiceHold"))){var hold=ui.executeShellCommand("input swipe 600 1613 600 1613 5500");}
                for(int i=0;i<9;i++){SystemClock.sleep(1100);capture(dir,"voice");}
            }
        }
        shell("input keyevent KEYCODE_HOME");
    }
    /** Tap the visible text itself, the way a user would. */
    private boolean tapLabel(String label)throws Exception{
        {var r=ui.getRootInActiveWindow();if(r==null)return false;
            try{for(var n:r.findAccessibilityNodeInfosByText(label)){
                if(!n.isVisibleToUser()||!label.contentEquals(n.getText()==null?"":n.getText()))continue;
                android.graphics.Rect b=new android.graphics.Rect();n.getBoundsInScreen(b);
                shell("input tap "+b.centerX()+" "+b.centerY());return true;}}
            finally{r.recycle();}}
        return false;
    }
    private void clickLabel(String label){
        {var r=ui.getRootInActiveWindow();if(r==null)return;
            try{for(var n:r.findAccessibilityNodeInfosByText(label)){var t=n;while(t!=null&&!t.isClickable())t=t.getParent();
                if(t!=null){t.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(800);return;}}}
            finally{r.recycle();}}
    }
    private void capture(File dir,String name)throws Exception{
        SystemClock.sleep(350); // let the overlay animation settle
        Bitmap b=ui.takeScreenshot();if(b==null)return;
        try(var out=new FileOutputStream(new File(dir,String.format(Locale.ROOT,"%02d-%s.png",++shot,name)))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
    }

    @Test public void captureFlows()throws Exception{
        var args=InstrumentationRegistry.getArguments();assumeTrue("true".equals(args.getString("captureFlows")));
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));new DeviceAuditSetupTest().prepareIsolatedAuditAccount();
        ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String originalServices=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        var touch=new TouchAutomationSettings(app);boolean previousTouch=touch.isEnabled();
        float font=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        int brightness=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS),brightnessMode=Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE);
        int volume=app.getSystemService(android.media.AudioManager.class).getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        File root=new File(app.getCacheDir(),"captures");root.mkdirs();
        try{
            String own=new ComponentName(app,ClickAccessibilityService.class).flattenToString();var services=new ArrayList<String>();
            if(originalServices!=null&&!originalServices.isBlank())services.addAll(Arrays.asList(originalServices.split(":")));services.remove(own);
            Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));SystemClock.sleep(250);
            services.add(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
            new GuidancePreferences(app).markHomeTutorialSeen();touch.setEnabled(false);
            for(String[] s:SCENARIOS){
                if(args.getString("captureScenario")!=null&&!args.getString("captureScenario").equals(s[0]))continue;
                File dir=new File(root,s[0]);dir.mkdirs();for(File f:Objects.requireNonNull(dir.listFiles()))f.delete();shot=0;
                ClickOverlayService flow=null;
                try{
                    new AuthenticatedHttpClient(app).post("/api/v1/voice-requests/interrupt-and-clear",new org.json.JSONObject()
                        .put("id",UUID.randomUUID().toString()).put("raw_goal","중단").put("clear_queued",true).toString());
                    for(String pkg:List.of("com.android.settings","com.coupang.mobile","kr.go.minwon.m","com.sec.android.app.clockpackage"))shell("am force-stop "+pkg);
                    if("open_app".equals(s[2])){
                        // Manual guidance: Click highlights the app icon, the user taps it.
                        touch.setEnabled(false);
                        open(com.click.HomeActivity.class);type(0,s[1]);capture(dir,"typed");shell("input keyevent KEYCODE_ENTER");
                        int[] last=null;long until=SystemClock.elapsedRealtime()+45000;int afterOpen=0,frames=0;boolean tapped=false;
                        while(SystemClock.elapsedRealtime()<until&&afterOpen<3){
                            SystemClock.sleep(1300);
                            ClickOverlayService cur=service();int[] target=null;boolean drawn=false;
                            if(cur!=null){final Object[] st=new Object[2];
                                inst.runOnMainSync(()->{st[0]=get(cur,"lastTargetBounds");var g=(com.click.ui.overlay.GuideOverlayView)get(cur,"guideOverlayView");
                                    st[1]=g!=null&&st[0]!=null&&g.targetMatches((int[])st[0]);});
                                target=(int[])st[0];drawn=Boolean.TRUE.equals(st[1]);}
                            capture(dir,"step");
                            var active=ui.getRootInActiveWindow();
                            if(active!=null&&String.valueOf(active.getPackageName()).contains("camera")){afterOpen++;continue;}
                            // The launcher icon highlight is drawn by the app-entry guide; tap the icon like a user.
                            if(!tapped&&++frames>=3){SystemClock.sleep(600);capture(dir,"highlight");tapped=tapLabel("카메라");continue;}
                            if(drawn&&target!=null&&(last==null||!Arrays.equals(last,target))){
                                last=target.clone();SystemClock.sleep(800);capture(dir,"highlight");
                                shell("input tap "+(target[0]+target[2])/2+" "+(target[1]+target[3])/2);
                            }
                        }
                        shell("am force-stop com.sec.android.app.camera");continue;
                    }
                    if("text_size".equals(s[2])){
                        open(com.click.designpreview.TextSizeSetupActivity.class);capture(dir,"default");
                        var r=ui.getRootInActiveWindow();android.graphics.Rect b=new android.graphics.Rect();
                        var c=r==null?null:r.findAccessibilityNodeInfosByViewId(app.getPackageName()+":id/text_size_setup_control");
                        if(c!=null&&!c.isEmpty()){c.get(0).getBoundsInScreen(b);
                            // Slider stops measured on the Galaxy Note20 screen (1080x2400).
                            shell("input tap 665 1281");SystemClock.sleep(1200);capture(dir,"larger");
                            shell("input tap 930 1281");SystemClock.sleep(1200);capture(dir,"largest");}
                        shell("input keyevent KEYCODE_HOME");continue;
                    }
                    if("feedback_send".equals(s[2])){
                        open(com.click.HomeActivity.class);type(0,s[1]);capture(dir,"typed");shell("input keyevent KEYCODE_ENTER");
                        SystemClock.sleep(3000);capture(dir,"feedback_window");
                        var fields=editables();
                        if(!fields.isEmpty()){var b=new android.os.Bundle();
                            b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"버튼 위치를 찾기 어려웠어요");
                            fields.get(fields.size()-1).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,b);SystemClock.sleep(800);}
                        capture(dir,"detail");
                        tapLabel("피드백 보내기");
                        for(int i=0;i<10;i++){SystemClock.sleep(150);var b2=ui.takeScreenshot();
                            if(b2!=null)try(var out=new FileOutputStream(new File(dir,String.format(Locale.ROOT,"%02d-sent.png",++shot)))){b2.compress(Bitmap.CompressFormat.PNG,100,out);}}
                        ClickOverlayService fb=service();if(fb!=null){inst.runOnMainSync(fb::onStopRequested);SystemClock.sleep(500);}
                        continue;
                    }
                    if("feedback".equals(s[2])){
                        // Negative feedback is not a task: Click apologises and offers a note field. Never sent.
                        open(com.click.HomeActivity.class);type(0,s[1]);capture(dir,"typed");shell("input keyevent KEYCODE_ENTER");
                        for(int i=0;i<4;i++){SystemClock.sleep(1500);capture(dir,"after");}
                        var fields=editables();
                        if(!fields.isEmpty()){var b=new android.os.Bundle();
                            b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"안내가 너무 빨라서 따라가기 어려웠어요");
                            fields.get(fields.size()-1).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,b);SystemClock.sleep(800);}
                        capture(dir,"detail");
                        ClickOverlayService fb=service();if(fb!=null){inst.runOnMainSync(fb::onStopRequested);SystemClock.sleep(500);}
                        continue;
                    }
                    if(Set.of("signup","login","text","voice").contains(s[2])){
                        accountOrInput(dir,s);continue;
                    }
                    if("menu".equals(s[2])){
                        app.startActivity(new Intent(app,com.click.MenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        SystemClock.sleep(2000);capture(dir,"menu");
                        shell("input swipe 540 1900 540 500 500");SystemClock.sleep(1200);capture(dir,"menu_bottom");
                        for(String row:List.of("앱 권한 확인","자동으로 도와주기","클릭 개선 참여","화면 설정","클루에 대해서","클릭 사용법","접근성 설정")){
                            app.startActivity(new Intent(app,com.click.MenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                            SystemClock.sleep(1500);
                            if(Set.of("클릭 사용법","접근성 설정").contains(row)){shell("input swipe 540 1900 540 500 500");SystemClock.sleep(1000);}
                            if(!tapLabel(row))continue;
                            SystemClock.sleep(1800);capture(dir,row.replace(' ','_'));
                        }
                        shell("input keyevent KEYCODE_HOME");continue;
                    }
                    if("app".equals(s[2])){
                        // Home and menu screens of the app itself; no request is sent.
                        app.startActivity(new Intent(app,com.click.HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        SystemClock.sleep(2500);capture(dir,"home");
                        app.startActivity(new Intent(app,com.click.MenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        SystemClock.sleep(2000);capture(dir,"menu");shell("input keyevent KEYCODE_HOME");continue;
                    }
                    if("settings".equals(s[2])){
                        shell("am start -W -a android.settings.SETTINGS -f 0x10008000");SystemClock.sleep(900);
                        // Start below the Samsung account card so no personal profile is captured.
                        for(int j=0;j<2;j++){var snap=ClickAccessibilityService.freshSnapshot();
                            if(snap!=null)ClickAccessibilityService.performSettingsScroll(true,snap.packageName,snap.activeWindowId);SystemClock.sleep(350);}
                    }
                    else if(!"home_request".equals(s[2])){
                        // Start inside the app so the personal home wallpaper is never captured.
                        app.startActivity(app.getPackageManager().getLaunchIntentForPackage(s[2]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        SystemClock.sleep(3000);
                        if(s[2].contains("clockpackage"))clickLabel("알람");
                    }
                    SystemClock.sleep(1200);
                    if(s[2].contains("coupang")){clickLabel("닫기");clickLabel("확인");SystemClock.sleep(800);}
                    boolean burst=s[2].contains(".")&&!"settings".equals(s[2])||"home_request".equals(s[2]);
                    if("home_request".equals(s[2])){
                        // Start from Click's own home: type the request and send it, so the app entry is visible.
                        open(com.click.HomeActivity.class);capture(dir,"home");
                        type(0,s[1]);capture(dir,"typed");shell("input keyevent KEYCODE_ENTER");
                    }else app.startService(new Intent(app,ClickOverlayService.class).putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL,s[1]));
                    if(burst){
                        flow=null;long until=SystemClock.elapsedRealtime()+60000;boolean confirmed=false;
                        while(SystemClock.elapsedRealtime()<until){
                            if(flow==null)flow=service();if(flow==null){SystemClock.sleep(100);continue;}
                            final ClickOverlayService cur=flow;final Object[] st=new Object[4];
                            inst.runOnMainSync(()->{st[0]=get(cur,"turnInFlight");st[1]=get(cur,"acceptedGoal");st[2]=get(cur,"agentConfirmationId");st[3]=get(cur,"resultPrompt");});
                            capture(dir,"burst");
                            if(st[3]!=null)break;
                            if(!confirmed&&st[1]==null&&!(boolean)st[0]){inst.runOnMainSync(()->call(cur,"confirmRequirementReview",new Class<?>[]{}));confirmed=true;touch.setEnabled(true);}
                            if(st[2]!=null&&!(boolean)st[0])inst.runOnMainSync(()->call(cur,"sendAgentTurn",new Class<?>[]{String.class,boolean.class,com.click.data.snapshot.ScreenSnapshot.class},"accepted",true,get(cur,"lastSubmittedScreen")));
                            SystemClock.sleep(1700);
                        }
                        touch.setEnabled(false);continue;
                    }
                    long deadline=SystemClock.elapsedRealtime()+150000;int[] lastShot=null;boolean reviewShot=false,approvalShot=false;int taps=0,results=0;
                    while(SystemClock.elapsedRealtime()<deadline){
                        if(flow==null)flow=service();if(flow==null){SystemClock.sleep(80);continue;}
                        final ClickOverlayService current=flow;final Object[] st=new Object[6];final String[] kind={null};
                        inst.runOnMainSync(()->{
                            st[0]=get(current,"turnInFlight");st[1]=get(current,"acceptedGoal");st[2]=get(current,"agentConfirmationId");
                            st[3]=get(current,"resultPrompt");st[4]=get(current,"lastTargetBounds");
                            var guide=(com.click.ui.overlay.GuideOverlayView)get(current,"guideOverlayView");
                            st[5]=guide!=null&&st[4]!=null&&guide.targetMatches((int[])st[4])&&guide.targetFrameElapsedMs()>0;
                            st[1]=st[1];kind[0]=guide==null?null:(String)get(guide,"actionType");
                        });
                        boolean busy=(boolean)st[0];
                        if(st[3]!=null){
                            capture(dir,"result");
                            if(!s[0].startsWith("6_")||++results>=2)break;
                            // Multiple requests: answer the first result and continue with the next one.
                            inst.runOnMainSync(()->{var panel=(android.view.View)get(current,"taskFlowView");
                                var yes=panel==null?null:panel.findViewById(com.click.R.id.task_flow_result_satisfied);if(yes!=null)yes.performClick();});
                            SystemClock.sleep(2500);lastShot=null;reviewShot=false;approvalShot=false;continue;
                        }
                        Object[] prep={null};inst.runOnMainSync(()->prep[0]=get(current,"goalPreparation"));
                        if(st[1]==null&&!busy&&prep[0]!=null){
                            var q=prep[0].getClass().getMethod("question").invoke(prep[0]);
                            if(q!=null){SystemClock.sleep(900);capture(dir,"question");break;}
                        }
                        if(st[1]==null&&!busy&&prep[0]!=null){
                            if(!reviewShot){SystemClock.sleep(900);capture(dir,"request");reviewShot=true;}
                            inst.runOnMainSync(()->call(current,"confirmRequirementReview",new Class<?>[]{}));SystemClock.sleep(600);continue;
                        }
                        if(st[2]!=null&&!busy){
                            if(!approvalShot){capture(dir,"approval");approvalShot=true;}
                            inst.runOnMainSync(()->call(current,"sendAgentTurn",new Class<?>[]{String.class,boolean.class,com.click.data.snapshot.ScreenSnapshot.class},
                                "accepted",true,get(current,"lastSubmittedScreen")));SystemClock.sleep(600);continue;
                        }
                        int[] target=(int[])st[4];
                        if(Boolean.TRUE.equals(st[5])&&!busy&&(lastShot==null||!Arrays.equals(lastShot,target))){
                            capture(dir,"guide");lastShot=target.clone();
                            if(++taps>8)break;
                            int cx=(target[0]+target[2])/2,cy=(target[1]+target[3])/2,h=target[3]-target[1],w=target[2]-target[0];
                            if("scroll".equals(kind[0]))shell("input swipe "+cx+" "+(target[1]+h*3/4)+" "+cx+" "+(target[1]+h/4)+" 450");
                            else if("drag".equals(kind[0]))shell("input swipe "+(target[0]+w/3)+" "+cy+" "+(target[0]+w*2/3)+" "+cy+" 450");
                            else shell("input tap "+cx+" "+cy);
                            SystemClock.sleep(1500);continue;
                        }
                        SystemClock.sleep(120);
                    }
                    if(shot==0)capture(dir,"state");
                }finally{
                    if(flow!=null){ClickOverlayService c=flow;inst.runOnMainSync(c::onStopRequested);SystemClock.sleep(700);}
                    app.stopService(new Intent(app,ClickOverlayService.class));
                    shell("settings put system font_scale "+font);shell("settings put system screen_brightness "+brightness);
                    shell("settings put system screen_brightness_mode "+brightnessMode);shell("cmd media_session volume --stream 3 --set "+volume);
                }
            }
        }finally{
            touch.setEnabled(previousTouch);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,originalServices==null?"":originalServices);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);ui.dropShellPermissionIdentity();
        }
    }
}
