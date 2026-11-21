package com.click.service.accessibility;

import android.app.UiAutomation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppEntryController;
import com.click.service.overlay.RequestedApp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DialpadEntryDeviceTest {
    @Test public void blockRequestArrivesAtSystemBlockListAndSavedContactResolvesToContacts()throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        var requested=RequestedApp.find(context,"전화번호 차단하고 싶어");
        var saved=RequestedApp.find(context,"저장된 연락처를 차단해줘");
        var contacts=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),0);
        assertNotNull(requested);assertNotNull(saved);assertNotNull(contacts);
        assertEquals(contacts.activityInfo.packageName,saved.packageName);
        var ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(info);
        var done=new CountDownLatch(1);var error=new AtomicReference<String>();
        var session=new AtomicReference<AppEntryController>();
        try{
            assertEquals(AppEntryController.Launch.STARTED,AppEntryController.launch(context,requested));
            instrumentation.runOnMainSync(()->{
                session.set(new AppEntryController(requested,new AppEntryController.Listener(){
                    public boolean canProceed(){return true;}
                    public ScreenSnapshot freshScreen(){
                        var root=ui.getRootInActiveWindow();if(root==null)return null;
                        try{return new AccessibilitySnapshotCollector().collect(root,String.valueOf(root.getPackageName()),context,null);}
                        finally{root.recycle();}
                    }
                    public AppEntryController.Launch launch(){return AppEntryController.launch(context,requested);}
                    public void waiting(){}
                    public void entered(){done.countDown();}
                    public void recovery(String message,Runnable retry){error.set(message);done.countDown();}
                }));session.get().start();
            });
            assertTrue("System block-list arrival",done.await(10,TimeUnit.SECONDS));assertNull(error.get(),error.get());
        }finally{
            if(session.get()!=null)instrumentation.runOnMainSync(session.get()::close);
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
    @Test public void contactsToRequestedKeypad() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        var ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var serviceInfo=ui.getServiceInfo();
        serviceInfo.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(serviceInfo);
        String goal="전화번호 키패드에 즐겨찾기 하고 싶어";
        var phone=RequestedApp.find(context,goal);
        var contacts=RequestedApp.find(context,"연락처 앱에서 즐겨찾기");
        assertNotNull("Default dialer must be installed",phone);
        assertNotNull("Contacts launcher entry must be installed",contacts);
        assertEquals(context.getSystemService(android.telecom.TelecomManager.class).getDefaultDialerPackage(),phone.packageName);
        var contactsEntry=context.getPackageManager().getLaunchIntentForPackage(contacts.packageName);
        assertNotNull(contactsEntry);
        var done=new CountDownLatch(1);
        var launches=new AtomicInteger();
        var error=new AtomicReference<String>();
        var entry=new AtomicReference<AppEntryController>();
        try {
            context.startActivity(contactsEntry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean observed=false;
            for(int i=0;i<25 && !observed;i++){
                var root=ui.getRootInActiveWindow();
                if(root!=null){observed=contacts.packageName.contentEquals(String.valueOf(root.getPackageName()));root.recycle();}
                if(!observed)SystemClock.sleep(150);
            }
            assertTrue("Contacts must be in the foreground before recovery",observed);
            instrumentation.runOnMainSync(()->{
                entry.set(new AppEntryController(phone,new AppEntryController.Listener(){
                    public boolean canProceed(){return true;}
                    public ScreenSnapshot freshScreen(){
                        var root=ui.getRootInActiveWindow();
                        if(root==null)return null;
                        try{
                            String pkg=String.valueOf(root.getPackageName());
                            // Observe only the foreground identity while Contacts is open.
                            if(!phone.packageName.equals(pkg))
                                return new ScreenSnapshot(pkg,Instant.now().toString(),1,1,List.of(),root.getWindowId());
                            var bounds=context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
                            return new AccessibilitySnapshotCollector().collect(root,pkg,bounds.width(),bounds.height(),null);
                        }finally{root.recycle();}
                    }
                    public AppEntryController.Launch launch(){launches.incrementAndGet();return AppEntryController.launch(context,phone);}
                    public void waiting(){}
                    public void entered(){done.countDown();}
                    public void recovery(String message,Runnable retry){error.set(message);done.countDown();}
                }));
                entry.get().start();
            });
            assertTrue("Wait for the native keypad",done.await(10,TimeUnit.SECONDS));
            assertNull(error.get(),error.get());
            assertEquals(1,launches.get());
        } finally {
            if(entry.get()!=null)instrumentation.runOnMainSync(entry.get()::close);
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
