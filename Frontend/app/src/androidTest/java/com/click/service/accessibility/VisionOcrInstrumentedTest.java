package com.click.service.accessibility;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.VisionOcrElement;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class VisionOcrInstrumentedTest {
    @Test public void measuresRecognitionAcrossThemeResolutionAndFontSize() throws Exception {
        Case[] cases = {
                new Case(1080, 2400, Color.WHITE, Color.BLACK, 56f),
                new Case(1080, 2400, Color.BLACK, Color.WHITE, 56f),
                new Case(720, 1600, Color.WHITE, Color.BLACK, 42f),
                new Case(1440, 3200, Color.BLACK, Color.WHITE, 84f),
        };
        int recognized = 0;
        long totalMs = 0;
        for (Case item : cases) {
            Result result = recognize(item);
            totalMs += result.processingTimeMs;
            String text = result.elements.toString().replace(" ", "").toLowerCase(Locale.ROOT);
            if (text.contains("디스플레이") && text.contains("글자크기")) recognized++;
        }
        double successRate = recognized / (double) cases.length;
        long averageMs = totalMs / cases.length;
        Log.i("ClickVisionEval", "cases=" + cases.length + " success_rate="
                + successRate + " average_processing_ms=" + averageMs);
        assertTrue("OCR target recall=" + successRate, successRate >= 0.75);
        assertTrue("OCR average latency=" + averageMs, averageMs < 3000);
    }

    private static Result recognize(Case item) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(item.background);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(item.foreground);
        paint.setTextSize(item.textSize);
        canvas.drawText("설정", item.width * 0.08f, item.height * 0.15f, paint);
        canvas.drawText("디스플레이", item.width * 0.08f, item.height * 0.35f, paint);
        canvas.drawText("글자 크기", item.width * 0.08f, item.height * 0.52f, paint);
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings",
                "2026-10-07T00:00:00Z", item.width, item.height, Collections.emptyList(), 1);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<VisionOcrElement>> elements = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong processing = new AtomicLong();
        VisionOcrEngine engine = new VisionOcrEngine();
        engine.recognize(bitmap, screen, new VisionOcrEngine.Callback() {
            @Override public void onSuccess(List<VisionOcrElement> value, long processingTimeMs) {
                elements.set(value);
                processing.set(processingTimeMs);
                latch.countDown();
            }

            @Override public void onFailure(String reason, Throwable error) {
                failure.set(error == null ? new AssertionError(reason) : error);
                latch.countDown();
            }
        });
        assertTrue("OCR timed out", latch.await(10, TimeUnit.SECONDS));
        engine.close();
        bitmap.recycle();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return new Result(elements.get(), processing.get());
    }

    private static final class Case {
        final int width, height, background, foreground;
        final float textSize;
        Case(int width, int height, int background, int foreground, float textSize) {
            this.width = width;
            this.height = height;
            this.background = background;
            this.foreground = foreground;
            this.textSize = textSize;
        }
    }

    private static final class Result {
        final List<VisionOcrElement> elements;
        final long processingTimeMs;
        Result(List<VisionOcrElement> elements, long processingTimeMs) {
            this.elements = elements;
            this.processingTimeMs = processingTimeMs;
        }
    }
}
