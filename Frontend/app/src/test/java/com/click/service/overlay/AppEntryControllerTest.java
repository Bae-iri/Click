package com.click.service.overlay;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppEntryControllerTest {
    private static ScreenSnapshot screen(String pkg) {
        return new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,List.of(),42);
    }
    private static final class Host implements AppEntryController.Listener {
        ScreenSnapshot current=screen("com.example.launcher");
        AppEntryController.Launch result=AppEntryController.Launch.STARTED;
        boolean allowed=true;
        int launches,entered,recoveries,waiting;
        Runnable retry;
        String message;
        public boolean canProceed(){return allowed;}
        public ScreenSnapshot freshScreen(){return current;}
        public AppEntryController.Launch launch(){launches++;return result;}
        public void waiting(){waiting++;}
        public void recovery(String message,Runnable retry){recoveries++;this.message=message;this.retry=retry;}
        public void entered(){entered++;}
    }
    private static void advance(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}

    @Test public void allPackagesUseOneVerifiedEntryWithoutAnotherApproval() {
        for(String pkg:List.of("com.coupang.mobile","kr.go.minwon.m","com.example.reader","com.android.settings")){
            Host host=new Host();
            try(var entry=new AppEntryController(pkg,"요청한 앱",host)){
                entry.start();advance(750);
                assertEquals(1,host.launches);assertEquals(0,host.entered);assertEquals(0,host.recoveries);
                host.current=screen(pkg);entry.screenChanged(host.current);advance(6000);
                assertEquals(1,host.entered);assertEquals(1,host.launches);assertEquals(0,host.recoveries);
            }
        }
    }
    @Test public void anAlreadyOpenAppIsNotRelaunched() {
        Host host=new Host();host.current=screen("com.example.reader");
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();assertEquals(1,host.entered);assertEquals(0,host.launches);assertEquals(0,host.waiting);
        }
    }
    @Test public void launchAcceptanceDoesNotCountAsObservedEntry() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();entry.screenChanged(screen("com.example.other"));advance(5000);
            assertEquals(0,host.entered);assertEquals(1,host.launches);assertEquals(1,host.recoveries);
            advance(10000);assertEquals(1,host.recoveries);assertEquals(1,host.launches);
        }
    }
    @Test public void failedEntryRequiresAnExplicitRetryAndRetainsTheDestination() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();advance(5000);host.retry.run();assertEquals(2,host.launches);
            host.current=screen("com.example.reader");entry.screenChanged(host.current);
            host.retry.run();assertEquals(2,host.launches);assertEquals(1,host.entered);
        }
    }
    @Test public void closingDiscardsBothLateScreensAndRetryCallbacks() {
        Host host=new Host();var entry=new AppEntryController("com.example.reader","읽기",host);
        entry.start();advance(5000);entry.close();
        host.current=screen("com.example.reader");entry.screenChanged(host.current);host.retry.run();advance(6000);
        assertEquals(0,host.entered);assertEquals(1,host.launches);assertEquals(1,host.recoveries);
    }
    @Test public void changedRequestOwnershipCannotContinueAnOldTask() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();host.allowed=false;host.current=screen("com.example.reader");advance(6000);
            entry.screenChanged(host.current);assertEquals(0,host.entered);assertEquals(0,host.recoveries);
        }
    }
    @Test public void pauseKeepsTheTaskAndResumeChecksTheAppBeforeContinuing() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();advance(1000);entry.pause();host.allowed=false;
            host.current=screen("com.example.reader");entry.screenChanged(host.current);advance(10000);
            assertEquals(0,host.entered);assertEquals(0,host.recoveries);
            host.allowed=true;entry.resume();assertEquals(1,host.entered);assertEquals(1,host.launches);
        }
    }
    @Test public void pausedTimeDoesNotConsumeTheEntryDeadline() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();advance(1000);entry.pause();advance(10000);entry.resume();advance(3000);
            assertEquals(0,host.recoveries);advance(1000);assertEquals(1,host.recoveries);
        }
    }
    @Test public void missingAppOrLockedDeviceDoesNotLoopOrStartTheTask() {
        for(var result:List.of(AppEntryController.Launch.UNAVAILABLE,AppEntryController.Launch.FAILED,AppEntryController.Launch.LOCKED)){
            Host host=new Host();host.result=result;
            try(var entry=new AppEntryController("com.example.reader","읽기",host)){
                entry.start();advance(6000);assertEquals(1,host.launches);assertEquals(1,host.recoveries);
                assertEquals(0,host.entered);assertEquals(0,host.waiting);
            }
        }
    }
    @Test public void packageWithoutAnActiveWindowDoesNotProveEntry() {
        Host host=new Host();
        try(var entry=new AppEntryController("com.example.reader","읽기",host)){
            entry.start();
            host.current=new ScreenSnapshot("com.example.reader",Instant.now().toString(),1080,2400,List.of(),null);
            entry.screenChanged(host.current);assertEquals(0,host.entered);
            host.current=screen("com.example.reader");entry.screenChanged(host.current);assertEquals(1,host.entered);
        }
    }
    @Test public void nativeLaunchUsesOnlyTheSelectedInstalledPackage() {
        Context context=mock(Context.class);PackageManager packages=mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packages);
        Intent launch=new Intent(Intent.ACTION_MAIN).setPackage("com.example.reader");
        when(packages.getLaunchIntentForPackage("com.example.reader")).thenReturn(launch);
        assertEquals(AppEntryController.Launch.STARTED,AppEntryController.launch(context,"com.example.reader"));
        verify(context).startActivity(launch);assertTrue((launch.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0);
        assertEquals(AppEntryController.Launch.UNAVAILABLE,AppEntryController.launch(context,"com.example.absent"));
        verify(context,times(1)).startActivity(any());
    }
}
