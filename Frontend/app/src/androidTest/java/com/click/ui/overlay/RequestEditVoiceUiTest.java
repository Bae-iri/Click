package com.click.ui.overlay;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.domain.goal.RequirementReview;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real attached views with a controlled transcript; does not record audio or call the server. */
@RunWith(AndroidJUnit4.class)
public class RequestEditVoiceUiTest {
    @Test public void microphoneDraftWorksAtNormalAndLargeTextSizes() throws Exception {
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)) {
            for(float scale:new float[]{1f,1.5f,2f}) scenario.onActivity(activity -> {
                var config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
                Context context=activity.createConfigurationContext(config);
                Replies replies=new Replies();TaskFlowOverlayView panel=new TaskFlowOverlayView(context,replies);
                activity.setContentView(panel);
                panel.requirements(new RequirementReview("휴대폰 전체 글자를 크게 해줘"),true);
                text(panel,"내용 수정").performClick();
                int width=Math.round(340*context.getResources().getDisplayMetrics().density);
                panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST));
                panel.layout(0,0,width,panel.getMeasuredHeight());
                View microphone=panel.findViewWithTag("request_edit_microphone");assertNotNull(microphone);
                assertTrue(microphone.isShown());assertTrue(microphone.getWidth()>=Math.round(48*context.getResources().getDisplayMetrics().density));
                microphone.performClick();assertTrue(replies.voiceOpened);assertTrue(replies.stopped);
                var target=panel.requestEditSpeechTarget();assertNotNull(target);assertTrue(target.apply("화면 밝기를 낮춰줘"));
                EditText editor=(EditText)description(panel,"입력한 내용 수정");assertEquals("화면 밝기를 낮춰줘",editor.getText().toString());
                assertNull(replies.rephrased);
                text(panel,"수정한 내용 확인").performClick();assertEquals("화면 밝기를 낮춰줘",replies.rephrased);
            });
        }
        JSONObject report=new JSONObject().put("passed",true).put("font_scales","1.0,1.5,2.0")
                .put("transcript_source","controlled_test_text").put("audio_recorded",false).put("server_posts",0);
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Files.write(new File(app.getCacheDir(),"request-edit-voice-ui.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static TextView text(View v,String value) {
        if(v instanceof TextView && value.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++) {var found=text(((ViewGroup)v).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private static View description(View v,String value) {
        CharSequence label=v.getContentDescription();
        if(label!=null && value.contentEquals(label))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++) {var found=description(((ViewGroup)v).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private static class Replies implements TaskFlowOverlayView.Listener {
        boolean voiceOpened,stopped;String rephrased;
        public void openInput(){voiceOpened=true;} public void stopGuidance(){stopped=true;}
        public void rephrase(String value){rephrased=value;}
        public void chooseGoal(JSONObject value){} public void clarify(String value){} public void result(String value){}
        public void adjust(String value){} public void control(String value){}
    }
}
