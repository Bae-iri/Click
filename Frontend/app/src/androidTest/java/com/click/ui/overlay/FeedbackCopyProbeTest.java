package com.click.ui.overlay;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Checks installed UI copy with disposable profiles; no network, feedback save or account changes. */
@RunWith(AndroidJUnit4.class)
public class FeedbackCopyProbeTest {
    @Test public void installedNegativeFeedbackUsesRequestedCopyAtNormalAndLargeFonts() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("feedbackCopyProbe")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        SharedPreferences profile=app.getSharedPreferences("feedback_copy_probe_profile",Context.MODE_PRIVATE);
        try {
            for(String name:new String[]{"","테스트사용자"}) for(float scale:new float[]{1f,1.5f}) {
                assertTrue(profile.edit().clear().putString("name",name).commit());
                instrumentation.runOnMainSync(()->{
                    Configuration configuration=new Configuration(app.getResources().getConfiguration());
                    configuration.fontScale=scale;
                    Context theme=new ContextThemeWrapper(app.createConfigurationContext(configuration),app.getApplicationInfo().theme);
                    Context context=new ContextWrapper(theme) {
                        @Override public SharedPreferences getSharedPreferences(String store,int mode) {
                            return "click_local_account".equals(store)?profile:super.getSharedPreferences(store,mode);
                        }
                    };
                    String expected="정말 죄송합니다. 아직 클릭이 성장 중이라 "
                            +(name.isEmpty()?"사용자님":name)+"께 불편을 드렸습니다.";
                    var panel=new FeedbackOverlayView(context,false,"죄송합니다. 불편했던 점을 알려주세요.",null,
                            new FeedbackOverlayView.Listener(){public void submit(String detail){fail("Must not submit feedback");} public void dismiss(){}});
                    assertEquals(expected,panel.guidanceMessage());
                    int width=Math.round(336*context.getResources().getDisplayMetrics().density);
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    panel.layout(0,0,width,panel.getMeasuredHeight());
                    TextView message=find(panel,expected);assertNotNull(message);assertNotNull(message.getLayout());
                    for(int line=0;line<message.getLineCount();line++) {
                        assertEquals(0,message.getLayout().getEllipsisCount(line));
                        assertTrue(message.getLayout().getLineMax(line)<=message.getWidth()-message.getCompoundPaddingLeft()-message.getCompoundPaddingRight()+1);
                    }
                    Bitmap bitmap=Bitmap.createBitmap(width,panel.getHeight(),Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);panel.draw(canvas);
                    String file="feedback-copy-"+(name.isEmpty()?"default":"named")+(scale>1?"-large":"")+".png";
                    try(var output=new FileOutputStream(new File(app.getCacheDir(),file))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
                    } catch(Exception error){throw new AssertionError(error);} finally {bitmap.recycle();}
                });
            }
        } finally {assertTrue(profile.edit().clear().commit());app.deleteSharedPreferences("feedback_copy_probe_profile");}
    }
    private static TextView find(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
}
