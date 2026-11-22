package com.click.service.accessibility;

import com.click.data.snapshot.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationTapEvidenceTest {
    private ScreenSnapshot screen(int window,String title) {
        var node=new AccessibilityNodeSnapshot("title",null,true,"com.android.settings",title,null,
            "android:id/title","android.widget.TextView","text",new int[]{158,179,502,274},
            false,true,false,false,false,null,window,false,null,null);
        return new ScreenSnapshot("com.android.settings","1970-01-01T00:00:02Z",1080,2400,List.of(node),window,true,1f);
    }
    @Test public void unrelatedOsValueChangesCannotVerifyATap() {
        var before=screen(5,"소리 및 진동").withAdjustmentValues(149,6);
        var after=screen(5,"소리 및 진동").withAdjustmentValues(150,7).withWifiState(3);
        assertNotEquals(before.contentFingerprint(),after.contentFingerprint());
        assertFalse(NavigationTapEvidence.nativeContentChanged(before,after));
    }
    @Test public void realNativePageOrWindowChangesAreObservable() {
        var before=screen(5,"소리 및 진동");
        assertTrue(NavigationTapEvidence.nativeContentChanged(before,screen(5,"벨소리")));
        assertTrue(NavigationTapEvidence.nativeContentChanged(before,screen(6,"소리 및 진동")));
    }
    @Test public void emptyOrUnavailableObservationNeverVerifiesATap() {
        var before=screen(5,"소리 및 진동");
        assertFalse(NavigationTapEvidence.nativeContentChanged(before,null));
        var empty=new ScreenSnapshot("com.android.settings","1970-01-01T00:00:02Z",1080,2400,List.of(),5,true,1f);
        assertFalse(NavigationTapEvidence.nativeContentChanged(before,empty));
    }
}
