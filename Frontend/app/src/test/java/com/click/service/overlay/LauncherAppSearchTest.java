package com.click.service.overlay;

import com.click.data.snapshot.*;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LauncherAppSearchTest {
    static final String HOME="com.sec.android.app.launcher";
    static AccessibilityNodeSnapshot node(String id,String parent,String text,String desc,String resource,String cls,boolean click) {
        return new AccessibilityNodeSnapshot(id,parent,true,HOME,text,desc,resource,cls,"button",new int[]{100,200,240,340},click,true,false,false,false,null,7);
    }
    static AccessibilityNodeSnapshot app(String id,String parent,String name){return node(id,parent,name,name,"icon","android.widget.TextView",true);}
    static AccessibilityNodeSnapshot folder(String id,String name){return node(id,null,name,name+", 폴더","folder_icon","FolderIcon",true);}
    static AccessibilityNodeSnapshot container(){return node("folder",null,null,null,"folder_content","android.view.ViewGroup",false);}
    static ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes){return new ScreenSnapshot(HOME,Instant.now().toString(),1080,2400,List.of(nodes),7);}
    static ScreenSnapshot drawer(AccessibilityNodeSnapshot... nodes){
        List<AccessibilityNodeSnapshot> list=new ArrayList<>(List.of(nodes));
        list.add(node("drawer",null,null,null,"apps_container","android.view.ViewGroup",false));
        return new ScreenSnapshot(HOME,Instant.now().toString(),1080,2400,list,7);
    }
    static LauncherAppSearch search(){return new LauncherAppSearch(Set.of("카메라"));}
    static ScreenSnapshot drawerPage(String label){
        return drawer(app("item",null,label)).withNavigation(new NavigationState("drawer",NavigationState.Axis.HORIZONTAL,null,true,-1,-1,-1,label));
    }
    @Test public void homeRedrawDoesNotProveDrawerEntryOrAuthorizePaging(){
        var s=search();assertEquals(LauncherAppSearch.Kind.OPEN_DRAWER,s.next(screen(app("a",null,"앱"))).kind());
        for(String clock:List.of("14:20","14:21"))assertEquals(LauncherAppSearch.Kind.WAIT,s.next(screen(app("clock",null,clock))).kind());
        assertEquals("app_drawer_not_observed",s.next(screen(app("clock",null,"14:22"))).reason());
        assertEquals(LauncherAppSearch.Kind.FINISHED,s.next(screen()).kind());
        var button=screen(node("apps",null,null,"앱스","all_apps_button","android.widget.Button",true));
        assertFalse(LauncherAppSearch.isDrawer(button));
    }
    @Test public void manualReturnFromDrawerOrNewsCannotReviveTheQueuedScroll(){
        for(boolean visitNews:List.of(true,false)){
            var s=search();var home=screen(app("phone",null,"전화"));
            s.next(home);var pending=s.next(drawerPage("갤러리"));
            assertEquals(LauncherAppSearch.Kind.SCROLL,pending.kind());
            if(visitNews)s.observeLocation(new ScreenSnapshot("com.samsung.android.app.spage",Instant.now().toString(),1080,2400,List.of(),12));
            s.observeLocation(home);assertFalse(s.pending(pending));
            assertEquals("left_launcher_search",s.next(home).reason());
            assertEquals(LauncherAppSearch.Kind.FINISHED,s.next(drawerPage("갤러리")).kind());
        }
    }
    @Test public void onlyPagesOwnedByTheDrawerCanBeScrolled(){
        assertTrue(LauncherAppSearch.drawerScrollable(drawerPage("지도")));
        var workspace=drawer(node("home",null,null,null,"workspace","PagedView",false))
                .withNavigation(new NavigationState("home",NavigationState.Axis.HORIZONTAL,null,true,-1,-1,-1,"home"));
        assertFalse(LauncherAppSearch.drawerScrollable(workspace));
        assertEquals("app_drawer_not_scrollable",search().next(workspace).reason());
    }
    @Test public void visibleAppIsSelectedBeforeDrawerOrFolders(){
        var step=search().next(screen(app("camera",null,"카메라"),folder("f","도구")));
        assertEquals(LauncherAppSearch.Kind.TAP_APP,step.kind());assertEquals("camera",step.target().nodeId);
    }
    @Test public void absentAppOpensDrawerWithVerticalGesturePlan(){assertEquals(LauncherAppSearch.Kind.OPEN_DRAWER,search().next(screen()).kind());}
    @Test public void explicitAppsButtonIsPreferred(){
        var button=node("apps",null,null,"앱스","apps_button","Button",true);
        assertEquals("apps",search().next(screen(button)).target().nodeId);
    }
    @Test public void drawerIsObservedThenFolderOpenedThenContentsRead(){
        var search=search();assertEquals(LauncherAppSearch.Kind.OPEN_DRAWER,search.next(screen()).kind());
        assertEquals(LauncherAppSearch.Kind.TAP_FOLDER,search.next(drawer(folder("f","도구"),app("preview","f","카메라"))).kind());
        var next=search.next(screen(container(),app("c","folder","카메라")));
        assertEquals(LauncherAppSearch.Kind.TAP_APP,next.kind());assertEquals("c",next.target().nodeId);
    }
    @Test public void stalePreviewIsClosedWithoutOpeningUnknownFolders() {
        var search=search();
        var parent=drawer(folder("f1","도구"),folder("f2","생활"),app("preview","f1","카메라"));
        assertEquals("f1",search.next(parent).target().nodeId);
        assertEquals(LauncherAppSearch.Kind.BACK,search.next(screen(container(),app("clock","folder","시계"))).kind());
        assertNotEquals(LauncherAppSearch.Kind.TAP_FOLDER,search.next(parent).kind());
    }
    @Test public void unknownFoldersAndAmbiguousPreviewsAreSkipped() {
        var unknown=drawer(folder("a","카메라"),folder("b","생활"),app("p","b","카메라 설정"));
        assertNotEquals(LauncherAppSearch.Kind.TAP_FOLDER,search().next(unknown).kind());
        var duplicates=drawer(folder("a","도구"),folder("b","생활"),app("p","a","카메라"),app("q","b","카메라"));
        assertNull(LauncherAppSearch.targetFolder(duplicates,Set.of("카메라")));
    }
    @Test public void closedFolderPreviewIsNeverAnAppTarget(){
        var s=screen(folder("f","카메라"),app("preview","f","카메라"));assertNull(LauncherAppSearch.app(s,Set.of("카메라")));
    }
    @Test public void folderNameAloneIsNotClassifiedAsFolder(){assertFalse(LauncherAppSearch.folder(app("f",null,"Shop")));}
    @Test public void backgroundAppIsNotSelectedThroughOpenFolder(){
        assertNull(LauncherAppSearch.app(screen(container(),app("background",null,"카메라")),Set.of("카메라")));
    }
    @Test public void nonClickableAppLabelResolvesToClickableParent(){
        var parent=node("cell",null,null,null,"icon","ViewGroup",true);
        var text=node("label","cell","카메라",null,"label","TextView",false);
        assertEquals("cell",LauncherAppSearch.app(screen(parent,text),Set.of("카메라")).nodeId);
    }
    @Test public void duplicateAppsRemainAmbiguous(){assertNull(LauncherAppSearch.app(screen(app("a",null,"카메라"),app("b",null,"카메라")),Set.of("카메라")));}
    @Test public void unchangedFolderActionIsNotRepeatedOrAssumedSuccessful(){
        var s=search();var screen=drawer(folder("f","도구"),app("preview","f","카메라"));
        assertEquals(LauncherAppSearch.Kind.TAP_FOLDER,s.next(screen).kind());
        assertEquals(LauncherAppSearch.Kind.WAIT,s.next(screen).kind());
        assertEquals(LauncherAppSearch.Kind.WAIT,s.next(screen).kind());
        assertEquals("action_not_confirmed",s.next(screen).reason());
    }
    @Test public void unrelatedScreenChangeIsNotProofOfFolderOpening(){
        var s=search();s.next(drawer(folder("f","도구"),app("preview","f","카메라")));
        assertEquals("folder_open_unconfirmed",s.next(drawer(app("a",null,"시계"))).reason());
    }
    @Test public void provenHomeFolderIsUsedBeforeTheDrawer(){
        var s=search();var screen=screen(folder("f","도구"),app("preview","f","카메라"));
        assertEquals(LauncherAppSearch.Kind.TAP_FOLDER,s.next(screen).kind());
    }
    @Test public void openingExistingFolderDoesNotSwipeBackgroundHome(){
        assertEquals(LauncherAppSearch.Kind.BACK,search().next(screen(container(),app("a","folder","시계"))).kind());
    }
    @Test public void onlyFolderOwnedCollectionMayBeScrolled(){
        var outside=screen(container()).withNavigation(new NavigationState("outside",NavigationState.Axis.HORIZONTAL,null,null,-1,-1,-1,"x"));
        assertFalse(LauncherAppSearch.folderScrollable(outside));
        assertTrue(LauncherAppSearch.folderScrollable(screen(container()).withNavigation(new NavigationState("folder",NavigationState.Axis.HORIZONTAL,null,null,-1,-1,-1,"x"))));
    }
    @Test public void foreignWindowAndKeyboardStopSearch(){
        assertEquals(LauncherAppSearch.Kind.FINISHED,search().next(new ScreenSnapshot("com.bank",Instant.now().toString(),1080,2400,List.of(),7)).kind());
        assertFalse(LauncherAppSearch.safe(screen().withWindowContext(new NativeWindowContext("UNKNOWN","APPLICATION",true))));
    }
    @Test public void nativeNodeChurnDoesNotProduceANewPage(){
        assertEquals(LauncherAppSearch.fingerprint(screen(app("a",null,"시계"))),LauncherAppSearch.fingerprint(screen(app("b",null,"시계"))));
    }
    @Test public void deadlineStopsEvenIfEveryPageChangesAndPauseDoesNotSpendIt(){
        long[] now={0};var s=new LauncherAppSearch(Set.of("카메라"),()->now[0]);s.next(screen());
        s.pause();now[0]=60000;s.resume();assertNotEquals(LauncherAppSearch.Kind.FINISHED,s.next(drawerPage("시계")).kind());
        now[0]=106000;assertEquals(LauncherAppSearch.Kind.FINISHED,s.next(drawerPage("갤러리")).kind());
    }
}
