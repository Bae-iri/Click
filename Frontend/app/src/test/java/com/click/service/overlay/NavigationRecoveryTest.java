package com.click.service.overlay;
import com.click.data.snapshot.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.service.overlay.NavigationRecovery.Action.*;
public class NavigationRecoveryTest {
 private ScreenSnapshot screen(String pkg, String title) {
  AccessibilityNodeSnapshot node=new AccessibilityNodeSnapshot("node_0_1","node_0",true,pkg,title,null,null,"TextView",null,new int[]{0,0,100,100},true,true,false,false,false,null,5);
  return new ScreenSnapshot(pkg,"2026-10-30T00:00:00Z",1080,2400,Arrays.asList(node),5);
 }
 private ScreenSnapshot settings(String title){return screen("com.android.settings",title);}
 @Test public void oneObservedWrongTapCanSuggestOneBack() {
  NavigationRecovery recovery=new NavigationRecovery();recovery.onPath(settings("설정"));
  recovery.unplannedTap();recovery.observe(settings("잘못 연 화면"));
  assertEquals(BACK,recovery.recommend(settings("잘못 연 화면")));
 }
 @Test public void settingsSubpageOffersBackWithoutHistoryAndAcceptsARecreatedWindow() {
  NavigationRecovery recovery=new NavigationRecovery();
  ScreenSnapshot wrong=settings("접근성");
  recovery.unplannedTap();recovery.unplannedTap();
  assertEquals(BACK,recovery.recommendSettingsBack());
  assertEquals(BACK,recovery.recommend(wrong));
  recovery.requested(BACK,wrong);
  ScreenSnapshot root=settings("설정");
  ScreenSnapshot recreated=new ScreenSnapshot(root.packageName,root.capturedAt,1080,2400,root.nodes,8);
  assertEquals(BACK,recovery.observe(recreated));
  assertEquals(NONE,recovery.hint());
  assertEquals(BACK,recovery.recommendSettingsBack());
  recovery.requested(BACK,recreated);
  assertEquals(BACK,recovery.observe(settings("디스플레이")));
 }
 @Test public void settingsBackWaitsThroughEmptyFramesAndReobservesWithoutForcingHome() {
  NavigationRecovery recovery=new NavigationRecovery();ScreenSnapshot wrong=settings("접근성");
  recovery.recommendSettingsBack();recovery.requested(BACK,wrong);
  assertEquals(NONE,recovery.observe(new ScreenSnapshot(wrong.packageName,wrong.capturedAt,1080,2400,List.of(),8)));
  assertEquals(BACK,recovery.awaiting());
  assertTrue(recovery.finishManualWait());
  assertEquals(NONE,recovery.hint());assertEquals(NONE,recovery.awaiting());
  assertEquals(BACK,recovery.recommendSettingsBack());
 }
 @Test public void settingsBackRecommendationDoesNotApplyAfterSwitchingToAnotherApp() {
  NavigationRecovery recovery=new NavigationRecovery();recovery.recommendSettingsBack();
  assertEquals(HOME,recovery.recommend(screen("com.example.other","다른 앱")));
 }
 @Test public void uncertainHistoryMultipleTapsOrOtherAppRequiresHome() {
  NavigationRecovery recovery=new NavigationRecovery();assertEquals(HOME,recovery.recommend(settings("알 수 없음")));
  recovery.onPath(settings("설정"));recovery.unplannedTap();recovery.unplannedTap();
  assertEquals(HOME,recovery.recommend(settings("잘못 연 화면")));
  recovery.onPath(settings("설정"));recovery.unplannedTap();
  assertEquals(HOME,recovery.recommend(screen("com.example.app","다른 앱")));
 }
 @Test public void unsuccessfulBackEscalatesToHome() {
  NavigationRecovery recovery=new NavigationRecovery();recovery.onPath(settings("설정"));recovery.unplannedTap();
  ScreenSnapshot wrong=settings("잘못 연 화면");recovery.observe(wrong);recovery.recommend(wrong);
  recovery.requested(BACK,wrong);recovery.observe(settings("여전히 모르는 화면"));
  assertEquals(HOME,recovery.hint());
 }
 @Test public void observedBackAndHomeKeepOrResetPath() {
  NavigationRecovery recovery=new NavigationRecovery();recovery.onPath(settings("설정"));recovery.onPath(settings("디스플레이"));
  assertEquals(BACK,recovery.observe(settings("설정")));
  assertEquals(HOME,recovery.observe(screen("com.android.launcher3","홈 화면")));
  recovery.unplannedTap();assertEquals(HOME,recovery.recommend(settings("모름")));
 }
 @Test public void screenRevisionOnlyDoesNotImplyNavigation() {
  NavigationRecovery recovery=new NavigationRecovery();ScreenSnapshot known=settings("설정");recovery.onPath(known);
  assertEquals(NONE,recovery.observe(known.withScreenRevision("another-revision")));
 }
 @Test public void physicalBackToEarlierKnownScreenIsAllowed() {
  NavigationRecovery recovery=new NavigationRecovery();ScreenSnapshot root=settings("설정"),child=settings("디스플레이");
  recovery.onPath(root);recovery.onPath(child);recovery.requested(BACK,child);
  assertEquals(BACK,recovery.observe(root));assertEquals(NONE,recovery.hint());
 }
 @Test public void recentsWindowIsNotHomeEvenInsideLauncher() {
  AccessibilityNodeSnapshot recent=new AccessibilityNodeSnapshot("node_0","root",true,"com.android.launcher3",null,null,"launcher:id/overview_panel","RecentsView",null,new int[]{0,0,1080,2400},false,true,false,false,false,null,5);
  ScreenSnapshot recents=new ScreenSnapshot("com.android.launcher3","2026-10-30T00:00:00Z",1080,2400,Arrays.asList(recent),5);
  assertFalse(NavigationRecovery.isHomeScreen(recents));
  NavigationRecovery recovery=new NavigationRecovery();recovery.onPath(settings("설정"));
  assertEquals(NONE,recovery.observe(recents));
 }
 @Test public void manualBackCanReobserveARecreatedSettingsWindowWithoutForcingHome() {
  NavigationRecovery recovery=new NavigationRecovery();
  ScreenSnapshot root=settings("설정"), child=settings("디스플레이");
  recovery.onPath(root); recovery.onPath(child);
  recovery.requestedByUser(BACK,child);
  ScreenSnapshot recreated=new ScreenSnapshot(root.packageName,root.capturedAt,1080,2400,root.nodes,8);
  assertEquals(BACK,recovery.observe(recreated));
  assertEquals(NONE,recovery.hint());
  recovery.onPath(recreated); recovery.observe(child); recovery.requestedByUser(BACK,child);
  assertEquals(BACK,recovery.observe(root));
  assertEquals(NONE,recovery.hint());
 }
 @Test public void manualBackWaitsThroughEmptyTransitionAndCanReleaseWithoutInventingNavigation() {
  NavigationRecovery recovery=new NavigationRecovery();ScreenSnapshot child=settings("디스플레이");
  recovery.onPath(child);recovery.requestedByUser(BACK,child);
  assertEquals(NONE,recovery.observe(new ScreenSnapshot(child.packageName,child.capturedAt,1080,2400,List.of(),8)));
  assertEquals(BACK,recovery.awaiting());
  assertTrue(recovery.finishManualWait());assertEquals(NONE,recovery.hint());
 }
 @Test public void physicalEventCannotWeakenAnExplicitBackRecovery() {
  NavigationRecovery recovery=new NavigationRecovery();ScreenSnapshot wrong=settings("모름");
  recovery.onPath(settings("설정"));recovery.requested(BACK,wrong);recovery.requestedByUser(BACK,wrong);
  assertEquals(NONE,recovery.observe(settings("다른 화면")));assertEquals(HOME,recovery.hint());
  assertFalse(recovery.finishManualWait());
 }
}
