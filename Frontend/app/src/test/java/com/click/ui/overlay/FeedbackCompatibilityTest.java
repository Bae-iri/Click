package com.click.ui.overlay;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28}, application = Application.class)
public class FeedbackCompatibilityTest {
    @Test public void feedbackOpensWithAnAccessibleHeadingOnOlderAndroid() {
        for (boolean positive : new boolean[]{false, true}) {
            var panel = new FeedbackOverlayView(RuntimeEnvironment.getApplication(), positive, "의견", "",
                    mock(FeedbackOverlayView.Listener.class));
            View heading = find(panel, positive ? "고마워요!" : "의견을 들려주세요");
            assertNotNull(heading);
            assertTrue(ViewCompat.isAccessibilityHeading(heading));
        }
    }
    private View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
