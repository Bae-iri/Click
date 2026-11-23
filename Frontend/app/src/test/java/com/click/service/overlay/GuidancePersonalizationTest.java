package com.click.service.overlay;

import android.app.Application;
import com.click.data.local.GuidancePreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class GuidancePersonalizationTest {
    private GuidancePreferences prefs(String name){var p=RuntimeEnvironment.getApplication().getSharedPreferences(name,0);p.edit().clear().commit();return new GuidancePreferences(p);}
    @Test public void feedbackWithoutRememberingChangesOnlyTheCurrentTask(){
        var prefs=prefs("transient");var guidance=new GuidancePersonalization(prefs);
        assertTrue(guidance.feedback(GuidancePreferences.Feedback.SLOWER,false).contains("이번 작업"));
        assertEquals(.75f,guidance.speechRate(),.001f);
        guidance.beginTask();assertEquals(.92f,guidance.speechRate(),.001f);assertFalse(prefs.remembers());
    }
    @Test public void explicitOptInRemembersPresentationAndRevocationErasesIt(){
        var prefs=prefs("remembered");var guidance=new GuidancePersonalization(prefs);
        guidance.feedback(GuidancePreferences.Feedback.SLOWER,true);
        guidance.feedback(GuidancePreferences.Feedback.MORE_DETAIL,true);
        var next=new GuidancePersonalization(prefs);assertEquals(.75f,next.speechRate(),.001f);assertTrue(next.detailed());
        prefs.remember(false);next.beginTask();assertEquals(.92f,next.speechRate(),.001f);assertFalse(next.detailed());
    }
    @Test public void repeatedRequestsAdaptOnlyAfterOptInAndTwoRequests(){
        var prefs=prefs("repeat");var guidance=new GuidancePersonalization(prefs);
        assertFalse(guidance.repeated());assertFalse(guidance.repeated());assertFalse(guidance.detailed());
        prefs.remember(true);assertFalse(guidance.repeated());assertTrue(guidance.repeated());assertTrue(guidance.detailed());
        assertTrue(new GuidancePersonalization(prefs).detailed());
    }
}
