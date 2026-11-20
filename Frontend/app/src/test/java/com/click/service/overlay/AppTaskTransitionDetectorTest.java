package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;
import static com.click.service.overlay.AppTaskTransitionDetector.Kind.*;

public class AppTaskTransitionDetectorTest {
    static final long WALL=1800000000000L;
    static final String[] PACKAGES={"org.example.reader","com.example.media","net.example.tools"};
    static AccessibilityNodeSnapshot button(String pkg,String id,String route,int window,int shift){
        return new AccessibilityNodeSnapshot(id,null,true,pkg,route,null,pkg+":id/"+route,"android.widget.Button",null,
            new int[]{10+shift,20,400+shift,120},true,true,false,false,false,null,window);
    }
    static ScreenSnapshot frame(String pkg,long at,int window,String route){return frame(pkg,at,window,route,0,"Title");}
    static ScreenSnapshot frame(String pkg,long at,int window,String route,int shift,String title){
        var heading=new AccessibilityNodeSnapshot("heading_"+at,null,true,pkg,title,null,pkg+":id/title","android.widget.TextView",null,
            new int[]{10,150,600,250},false,true,false,false,false,null,window,false,null,null,false,null,true,false);
        return new ScreenSnapshot(pkg,Instant.ofEpochMilli(WALL+at).toString(),1080,2400,
            List.of(button(pkg,"node_"+at,route,window,shift),heading),window)
            .withWindowContext(new NativeWindowContext("UNKNOWN","APPLICATION",false));
    }
    @Test public void stableTransitionUsesTwoDistinctNativeReadsAndBothClocksAcrossThreeApps(){
        for(String pkg:PACKAGES){
            var detector=new AppTaskTransitionDetector();var before=frame(pkg,0,42,"Alpha");
            assertEquals(CHANGING,detector.observe(before,frame(pkg,100,43,"Beta"),100,WALL+100).kind());
            assertEquals(CHANGING,detector.observe(before,frame(pkg,449,43,"Beta"),449,WALL+449).kind());
            assertEquals(STABLE_CHANGED,detector.observe(before,frame(pkg,450,43,"Beta"),450,WALL+450).kind());
        }
    }
    @Test public void cachedFramesAndAdvancedNativeTimestampAloneDoNotManufactureTwoFreshReads(){
        String pkg=PACKAGES[0];var before=frame(pkg,0,42,"Alpha");var after=frame(pkg,100,42,"Beta");
        var detector=new AppTaskTransitionDetector();detector.observe(before,after,100,WALL+100);
        assertEquals(CHANGING,detector.observe(before,after,1000,WALL+1000).kind());
        detector.reset();detector.observe(before,frame(pkg,100,42,"Beta"),100,WALL+100);
        assertEquals(CHANGING,detector.observe(before,frame(pkg,900,42,"Beta"),110,WALL+900).kind());
    }
    @Test public void nodePathsGeometryStaticHeadingAndWindowIdAloneAreNotSemanticProgress(){
        for(String pkg:PACKAGES){
            var detector=new AppTaskTransitionDetector();var before=frame(pkg,0,42,"Alpha",0,"Old title");
            detector.observe(before,frame(pkg,100,43,"Alpha",30,"New title"),100,WALL+100);
            assertEquals(STABLE_SAME,detector.observe(before,frame(pkg,450,43,"Alpha",50,"Third title"),450,WALL+450).kind());
        }
    }
    @Test public void afterWindowMustStayStableButNavigationMayOpenADifferentWindow(){
        String pkg=PACKAGES[0];var before=frame(pkg,0,42,"Alpha");var detector=new AppTaskTransitionDetector();
        detector.observe(before,frame(pkg,100,43,"Beta"),100,WALL+100);
        assertEquals(CHANGING,detector.observe(before,frame(pkg,450,44,"Beta"),450,WALL+450).kind());
        assertEquals(STABLE_CHANGED,detector.observe(before,frame(pkg,800,44,"Beta"),800,WALL+800).kind());
    }
    @Test public void changingInteractiveContentNeverBecomesStableMerelyBecauseEventsContinue(){
        String pkg=PACKAGES[0];var before=frame(pkg,0,42,"Alpha");var detector=new AppTaskTransitionDetector();
        for(int at=100;at<10000;at+=200)
            assertEquals(CHANGING,detector.observe(before,frame(pkg,at,42,"Feed"+at),at,WALL+at).kind());
    }
    @Test public void publicSameAppDialogCanProvideTransitionEvidenceWithoutBecomingSuccess(){
        for(String pkg:PACKAGES){
            var before=frame(pkg,0,42,"Alpha");var detector=new AppTaskTransitionDetector();
            var first=frame(pkg,100,43,"Options").withWindowContext(new NativeWindowContext("UNKNOWN","DIALOG",false));
            var second=frame(pkg,450,43,"Options").withWindowContext(new NativeWindowContext("UNKNOWN","DIALOG",false));
            assertEquals(CHANGING,detector.observe(before,first,100,WALL+100).kind());
            assertEquals(STABLE_CHANGED,detector.observe(before,second,450,WALL+450).kind());
        }
    }
    @Test public void passwordDialogAndDifferentPackagePermissionWindowNeverProvideProgress(){
        String pkg=PACKAGES[0];var before=frame(pkg,0,42,"Alpha");
        var password=new AccessibilityNodeSnapshot("password",null,true,pkg,"PRIVATE",null,pkg+":id/password","android.widget.EditText",null,
            new int[]{10,20,400,120},true,true,false,false,false,null,43,true,null,null,true);
        var privateDialog=new ScreenSnapshot(pkg,Instant.ofEpochMilli(WALL+100).toString(),1080,2400,List.of(password),43)
            .withWindowContext(new NativeWindowContext("UNKNOWN","DIALOG",false));
        var permissions=frame("org.example.permissions",100,43,"Permission").withWindowContext(new NativeWindowContext("UNKNOWN","DIALOG",false));
        for(var invalid:List.of(privateDialog,permissions)){
            var evidence=new AppTaskTransitionDetector().observe(before,invalid,100,WALL+100);
            assertEquals(UNOBSERVABLE,evidence.kind());assertFalse(evidence.safeCurrent());
        }
    }
    @Test public void privacyFreshnessPackageAndNativeWindowBoundariesFailClosed(){
        String pkg=PACKAGES[0];var before=frame(pkg,0,42,"Alpha");
        var password=new AccessibilityNodeSnapshot("password",null,true,pkg,"PRIVATE",null,pkg+":id/password","android.widget.EditText",null,
            new int[]{10,20,400,120},true,true,false,false,false,null,42,true,null,null,true);
        var protectedFrame=new ScreenSnapshot(pkg,Instant.ofEpochMilli(WALL+100).toString(),1080,2400,List.of(password),42);
        var overlay=frame(pkg,100,42,"Beta").withWindowContext(new NativeWindowContext("UNKNOWN","OVERLAY",false));
        for(ScreenSnapshot invalid:List.of(protectedFrame,overlay,frame(PACKAGES[1],100,42,"Beta"),frame(pkg,3000,42,"Beta"))){
            var evidence=new AppTaskTransitionDetector().observe(before,invalid,100,WALL+100);
            assertEquals(UNOBSERVABLE,evidence.kind());assertFalse(evidence.safeCurrent());
        }
        assertEquals(UNOBSERVABLE,new AppTaskTransitionDetector().observe(before,frame(pkg,100,42,"Beta"),20000,WALL+20000).kind());
        assertEquals(UNOBSERVABLE,new AppTaskTransitionDetector().observe(before,null,100,WALL+100).kind());
        assertNull(AppTaskTransitionDetector.currentRoute(protectedFrame,WALL+100));
    }
}
