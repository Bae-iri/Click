package com.click.ui.overlay;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import com.click.ui.common.ClickUi;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class, qualifiers="ko-rKR-w360dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeedbackPreviewTest {
    private void render(String name, boolean positive) throws Exception {
        var context=RuntimeEnvironment.getApplication();
        var view=new FeedbackOverlayView(context,positive,
                "죄송합니다. 클릭이 아직 성장 중이라 부족한 점이 있어요. 어떤 점이 불편하셨는지 적어주시면 개선에 참고할게요.",
                "null",mock(FeedbackOverlayView.Listener.class));
        int width=ClickUi.dp(context,336);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,width,view.getMeasuredHeight());
        Bitmap bitmap=Bitmap.createBitmap(width,view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);view.draw(canvas);
        Path path=Path.of(System.getProperty("click.contractOutput"),"feedback-ui",name+".png");
        Files.createDirectories(path.getParent());
        try(var output=Files.newOutputStream(path)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    @Test public void negativeFeedbackPreview() throws Exception { render("negative",false); }
    @Test public void negativeFeedbackLargeTextPreview() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        render("negative-large-text",false);
    }
}
