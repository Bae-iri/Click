package com.click.service.overlay;
import com.click.data.snapshot.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class SettingsPageNavigationTest {
 private SettingsPageNavigation policy() throws Exception {
  try(var in=getClass().getResourceAsStream("/vision_vocabulary.json")){return new SettingsPageNavigation(VisionPrivacy.load(in));}
 }
 private ScreenSnapshot page(String title,int window,int y,boolean sensitive) {
  var heading=new AccessibilityNodeSnapshot("heading",null,true,"com.android.settings",title,null,null,
   "android.widget.TextView","text",new int[]{20,20,900,80},false,true,false,false,false,null,window,sensitive);
  var row=new AccessibilityNodeSnapshot("row",null,true,"com.android.settings","연결",null,null,
   "android.widget.TextView","menu_item",new int[]{20,y,900,y+80},true,true,false,false,false,null,window);
  return new ScreenSnapshot("com.android.settings","2026-11-10T00:00:00Z",1080,2400,List.of(heading,row),window);
 }
 @Test public void otherNativePageIsNavigationAndCorrectPageCanRejoin() throws Exception {
  var pages=policy();ScreenSnapshot home=page("설정",1,300,false),wrong=page("블루투스",1,300,false),correct=page("디스플레이",1,300,false);
  assertEquals(SettingsPageNavigation.Change.OFF_ROUTE,pages.changed(home,wrong,"font_size"));
  assertEquals(SettingsPageNavigation.Change.ON_ROUTE,pages.changed(wrong,correct,"font_size"));
 }
 @Test public void scrollingValuesWindowChurnAndSensitiveScreensNeverUnlockWaiting() throws Exception {
  var before=page("설정",1,300,false);var after=page("설정",2,500,false).withWifiState(3).withAdjustmentValues(220,7);
  assertEquals(SettingsPageNavigation.Change.NONE,policy().changed(before,after,"font_size"));
  assertEquals(SettingsPageNavigation.Change.NONE,policy().changed(before,page("디스플레이",1,300,true),"font_size"));
  assertEquals(SettingsPageNavigation.Change.NONE,policy().changed(before,null,"font_size"));
 }
}
