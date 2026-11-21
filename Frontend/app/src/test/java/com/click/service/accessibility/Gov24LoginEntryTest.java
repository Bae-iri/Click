package com.click.service.accessibility;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=android.app.Application.class)
public class Gov24LoginEntryTest {
    private AccessibilityNodeInfo node(String text,boolean click,boolean input,boolean password,Rect bounds,AccessibilityNodeInfo... children) {
        var n=mock(AccessibilityNodeInfo.class);when(n.getPackageName()).thenReturn("kr.go.minwon.m");
        when(n.getText()).thenReturn(text);when(n.getClassName()).thenReturn(input?"android.widget.EditText":"android.view.View");
        when(n.isClickable()).thenReturn(click);when(n.isEnabled()).thenReturn(true);when(n.isVisibleToUser()).thenReturn(true);
        when(n.isEditable()).thenReturn(input);when(n.isPassword()).thenReturn(password);when(n.getWindowId()).thenReturn(42);
        doAnswer(i->{((Rect)i.getArgument(0)).set(bounds);return null;}).when(n).getBoundsInScreen(any());
        when(n.getChildCount()).thenReturn(children.length);for(int i=0;i<children.length;i++)when(n.getChild(i)).thenReturn(children[i]);return n;
    }
    private AccessibilityNodeInfo home(Rect loginBounds,AccessibilityNodeInfo... extra) {
        var children=new java.util.ArrayList<AccessibilityNodeInfo>();
        children.add(node("정부24",false,false,false,new Rect(0,0,100,40)));
        children.add(node("자주찾는 서비스",false,false,false,new Rect(0,100,400,140)));
        children.add(node("로그인",true,false,false,loginBounds));java.util.Collections.addAll(children,extra);
        return node(null,false,false,false,new Rect(0,0,1080,2400),children.toArray(new AccessibilityNodeInfo[0]));
    }
    @Test public void homeSearchNeverBecomesAuthenticationAndItsValueIsNeverRead() {
        var search=node("private search value",true,true,false,new Rect(100,200,900,280));
        var target=Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),search));
        assertNotNull(target);assertTrue(target.visible);assertEquals("node_0_2",target.nodeId);verify(search,never()).getText();
    }
    @Test public void clippedHomeLoginIsStillFoundForNativeReveal() {
        var target=Gov24LoginEntry.inspect(home(new Rect(700,92,820,92)));
        assertNotNull(target);assertFalse(target.visible);
    }
    @Test public void civilServicesHomeUsesTheSameAnchorAsThePublicPrivacyFilter() {
        var root=node(null,false,false,false,new Rect(0,0,1080,2400),
            node("정부24",false,false,false,new Rect(0,0,100,40)),
            node("민원서비스",false,false,false,new Rect(0,100,400,140)),
            node("로그인",true,false,false,new Rect(700,50,820,100)));
        assertNotNull(Gov24LoginEntry.inspect(root));
    }
    @Test public void credentialPopupAndPasswordsCannotUseTheHomeRoute() {
        var a=node(null,true,true,false,new Rect(0,100,400,180));
        var b=node(null,true,true,false,new Rect(0,200,400,280));
        assertNull(Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),a,b)));
        assertNull(Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),node(null,true,true,true,new Rect(0,100,400,180)))));
    }
    @Test public void consentCertificateAndForegroundChoicesTakePrecedenceOverHome() {
        for(String label:new String[]{"인증서","서비스 이용에 대한 동의","간편인증","카카오톡"})
            assertNull(Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),node(label,true,false,false,new Rect(0,200,400,280)))));
    }
    @Test public void unrelatedOrAmbiguousScreensCannotSupplyALoginTarget() {
        var root=home(new Rect(700,50,820,100));when(root.getPackageName()).thenReturn("other.app");assertNull(Gov24LoginEntry.inspect(root));
        assertNull(Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),node("로그인",true,false,false,new Rect(0,500,400,580)))));
    }
    @Test public void virtualProviderLabelCannotExposeTheHomeBehindItsDialog() {
        assertNull(Gov24LoginEntry.inspect(home(new Rect(700,50,820,100),
            node("카카오톡",false,false,false,new Rect(0,200,400,280)))));
    }
    @Test public void childLabelIdentifiesItsButtonWithoutPromotingTheWholeHome() {
        var child=node("로그인",false,false,false,new Rect(720,60,800,90));
        var button=node(null,true,false,false,new Rect(700,50,820,100),child);
        var root=node(null,true,false,false,new Rect(0,0,1080,2400),
            node("정부24",false,false,false,new Rect(0,0,100,40)),
            node("자주찾는 서비스",false,false,false,new Rect(0,100,400,140)),button);
        var target=Gov24LoginEntry.inspect(root);
        assertNotNull(target);assertEquals("node_0_2",target.nodeId);assertArrayEquals(new int[]{700,50,820,100},target.bounds);
        when(button.isClickable()).thenReturn(false);
        assertNull("The page contains other labels and cannot stand in for the button",Gov24LoginEntry.inspect(root));
    }
    @Test public void mixedChildLabelsCannotNameALoginButtonAndNeverReadSearchValues() {
        var search=node("PRIVATE_QUERY",true,true,false,new Rect(720,60,800,90));
        var button=node(null,true,false,false,new Rect(700,50,820,100),
            node("로그인",false,false,false,new Rect(720,60,800,90)),search);
        var root=node(null,false,false,false,new Rect(0,0,1080,2400),
            node("정부24",false,false,false,new Rect(0,0,100,40)),
            node("자주찾는 서비스",false,false,false,new Rect(0,100,400,140)),button);
        assertNull(Gov24LoginEntry.inspect(root));verify(search,never()).getText();
    }
}
