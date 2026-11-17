package com.click;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.click.designpreview.OnboardingActivity;
import com.click.designpreview.TextSizeSetupActivity;
import com.click.designpreview.R;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUiSpeed;
import com.click.ui.overlay.TaskFlowOverlayView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class, qualifiers="ko-rKR-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiSpeedSettingsTest {
    @Test public void onboardingAndSettingsShareSpeedAndKeepTextSizeIndependent() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        int speed=ClickUiSpeed.level(app), size=ClickTextScale.level(app);
        animationScale(1f); ClickUiSpeed.saveLevel(app,1); ClickTextScale.saveLevel(app,1);
        try (var setup=Robolectric.buildActivity(TextSizeSetupActivity.class).setup()) {
            assertEquals(1,((SeekBar)setup.get().findViewById(R.id.ui_speed_scale)).getProgress());
            select(setup.get().findViewById(R.id.ui_speed_scale),0);
            long slow=setup.get().findViewById(R.id.ui_speed_preview).animate().getDuration();
            assertEquals("느리게",((TextView)setup.get().findViewById(R.id.ui_speed_current)).getText().toString());
            select(setup.get().findViewById(R.id.ui_speed_scale),2);
            long fast=setup.get().findViewById(R.id.ui_speed_preview).animate().getDuration();
            assertTrue("The preview must actually become faster",fast<slow);
            assertEquals(1,ClickTextScale.level(app));
            select(setup.get().findViewById(R.id.text_size_scale),3);
            assertEquals(2,ClickUiSpeed.level(app));
            setup.recreate();
            assertEquals(2,((SeekBar)setup.get().findViewById(R.id.ui_speed_scale)).getProgress());
            captureSetup(setup.get(),"setup-large");
            setup.get().findViewById(R.id.text_size_setup_continue).performClick();
            var introduction=Shadows.shadowOf(setup.get()).getNextStartedActivity();
            assertEquals(OnboardingActivity.class.getName(),introduction.getComponent().getClassName());
            try (var onboarding=Robolectric.buildActivity(OnboardingActivity.class,introduction).setup()) {
                onboarding.get().findViewById(R.id.onboarding_start).performClick();
                assertEquals(fast,onboarding.get().findViewById(R.id.onboarding_body).animate().getDuration());
            }
            try (var settings=Robolectric.buildActivity(DisplaySettingsActivity.class).setup()) {
                assertEquals(2,((SeekBar)settings.get().findViewById(R.id.ui_speed_scale)).getProgress());
                select(settings.get().findViewById(R.id.ui_speed_scale),0);
                assertEquals(slow,settings.get().findViewById(R.id.ui_speed_preview).animate().getDuration());
                select(settings.get().findViewById(R.id.text_size_scale),1);
                assertEquals(0,ClickUiSpeed.level(app));
                settings.recreate();
                assertEquals(0,((SeekBar)settings.get().findViewById(R.id.ui_speed_scale)).getProgress());
                assertEquals(1,((SeekBar)settings.get().findViewById(R.id.text_size_scale)).getProgress());
                captureSetup(settings.get(),"settings-normal-text");
            }
        } finally { ClickUiSpeed.saveLevel(app,speed); ClickTextScale.saveLevel(app,size); animationScale(1f); }
    }

    @Test public void activeGuidanceReadsUpdatedSpeedAndDisabledAnimationsStayVisible() {
        var app=RuntimeEnvironment.getApplication();int original=ClickUiSpeed.level(app);
        animationScale(1f);
        try (var activity=Robolectric.buildActivity(Activity.class).setup()) {
            TaskFlowOverlayView panel=new TaskFlowOverlayView(activity.get(),mock(TaskFlowOverlayView.Listener.class));
            activity.get().setContentView(panel);
            ClickUiSpeed.saveLevel(app,0);
            panel.progress("글자 크기",null,"첫 번째 안내",false);
            View content=ReflectionHelpers.getField(panel,"content");
            long slow=content.animate().getDuration();
            ClickUiSpeed.saveLevel(app,2);
            panel.progress("글자 크기",null,"다음 안내",false);
            assertTrue(content.animate().getDuration()<slow);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(1f,content.getAlpha(),.001f);
            animationScale(0f);
            panel.progress("글자 크기",null,"바로 보이는 안내",false);
            assertEquals(1f,content.getAlpha(),.001f);
            assertEquals(0f,content.getTranslationY(),.001f);
            assertEquals(0L,ClickUiSpeed.duration(app,240L));
            assertTrue(panel.guidanceMessage().contains("바로 보이는 안내"));
        } finally { animationScale(1f); ClickUiSpeed.saveLevel(app,original); }
    }

    @Test @Config(qualifiers="ko-rKR-w320dp-h640dp-xhdpi")
    public void largeSystemAndAppTextKeepSpeedControlsReachable() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        int speed=ClickUiSpeed.level(app), size=ClickTextScale.level(app);
        float systemScale=app.getResources().getConfiguration().fontScale;
        try {
            RuntimeEnvironment.setFontScale(1.5f);
            ClickTextScale.saveLevel(app,3); ClickUiSpeed.saveLevel(app,0);
            try (var setup=Robolectric.buildActivity(TextSizeSetupActivity.class).setup()) {
                captureSetup(setup.get(),"setup-small-screen-large-system-text");
            }
            try (var settings=Robolectric.buildActivity(DisplaySettingsActivity.class).setup()) {
                captureSetup(settings.get(),"settings-small-screen-large-system-text");
            }
        } finally {
            ClickUiSpeed.saveLevel(app,speed); ClickTextScale.saveLevel(app,size);
            RuntimeEnvironment.setFontScale(systemScale);
        }
    }

    private static void animationScale(float scale) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class,scale));
    }
    private static void select(SeekBar bar,int level) {
        Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,level);
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args));
    }
    private void captureSetup(Activity activity,String name) throws Exception {
        View root=activity.findViewById(android.R.id.content);
        int width=activity.getResources().getDisplayMetrics().widthPixels;
        int height=activity.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        capture(root,name+"-top");
        View control=activity.findViewById(R.id.ui_speed_scale);
        for (var parent=control.getParent();parent!=null;parent=parent.getParent()) {
            if (parent instanceof ScrollView) { ((ScrollView)parent).scrollTo(0,10000);break; }
        }
        capture(root,name+"-speed");
        android.graphics.Rect sliderBounds=new android.graphics.Rect();
        assertTrue(control.getGlobalVisibleRect(sliderBounds));assertEquals(control.getHeight(),sliderBounds.height());
        assertReadable(activity.findViewById(R.id.ui_speed_current));
        assertReadable(activity.findViewById(R.id.ui_speed_preview));
        assertReadable(activity.findViewById(R.id.ui_speed_replay));
        if (activity instanceof TextSizeSetupActivity) {
            android.graphics.Rect visible=new android.graphics.Rect();
            View next=activity.findViewById(R.id.text_size_setup_continue);
            assertTrue(next.getGlobalVisibleRect(visible));assertEquals(next.getHeight(),visible.height());
        }
    }
    private void capture(View view,String name) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);view.draw(canvas);
        Path file=Path.of(System.getProperty("click.contractOutput"),"ui-speed",name+".png");
        Files.createDirectories(file.getParent());
        try (var out=Files.newOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
        bitmap.recycle();
    }
    private void assertReadable(TextView text) {
        assertNotNull(text.getLayout());
        assertTrue(text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom());
        for(int line=0;line<text.getLineCount();line++)assertEquals(0,text.getLayout().getEllipsisCount(line));
    }
}
