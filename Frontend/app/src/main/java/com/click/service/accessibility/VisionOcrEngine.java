package com.click.service.accessibility;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.VisionElementMapper;
import com.click.data.snapshot.VisionOcrElement;
import com.click.data.snapshot.VisionSafetyPolicy;
import com.click.data.snapshot.VisionImageRegion;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Runs bundled Korean/Latin OCR on a transient bitmap and returns only derived text boxes. */
final class VisionOcrEngine {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?:\\d[ -]?){8,19}");
    private final TextRecognizer recognizer;

    VisionOcrEngine() {
        this(TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()));
    }

    VisionOcrEngine(TextRecognizer recognizer) { this.recognizer = recognizer; }

    interface Callback {
        void onSuccess(List<VisionOcrElement> elements, long processingTimeMs);
        void onFailure(String reason, Throwable error);
    }

    void recognize(Bitmap source, ScreenSnapshot screen, Callback callback) {
        recognize(source, screen, "full_frame", null, callback);
    }

    void recognize(Bitmap source, ScreenSnapshot screen, String strategy, int[] focus, Callback callback) {
        long started = SystemClock.elapsedRealtime();
        if (!source.isMutable()) { callback.onFailure("bitmap_not_mutable", null); return; }
        maskPrivateRegions(source, screen);
        // Every capture gets a full-frame safety pass before any crop can hide context.
        process(source, false, new TextCallback() {
            public void success(Text result) {
                if (!VisionSafetyPolicy.assessVisibleText(result.getText()).allowed) {
                    callback.onFailure("sensitive_screen_content", null); return;
                }
                if ("full_frame".equals(strategy)) {
                    finish(lines(result, new VisionImageRegion(0, 0, source.getWidth(), source.getHeight()),
                            source.getWidth(), source.getHeight()), source, screen, started, callback);
                    return;
                }
                // Mask entire private OCR blocks before processing subregions again.
                Canvas canvas = new Canvas(source);
                Paint mask = new Paint(); mask.setColor(Color.BLACK);
                for (Text.TextBlock block : result.getTextBlocks()) {
                    if (containsPrivateData(block.getText()) && block.getBoundingBox() != null)
                        canvas.drawRect(block.getBoundingBox(), mask);
                }
                VisionImageRegion[] regions = "focus_or_tiles".equals(strategy)
                        ? VisionImageRegion.regions(source.getWidth(), source.getHeight(), focus)
                        : new VisionImageRegion[]{new VisionImageRegion(0, 0, source.getWidth(), source.getHeight())};
                recognizeRegion(source, screen, regions, 0, new ArrayList<>(), started, callback);
            }
            public void failure(Throwable error) { callback.onFailure("ocr_failed", error); }
        });
    }

    private interface TextCallback { void success(Text text); void failure(Throwable error); }

    private void process(Bitmap input, boolean owned, TextCallback callback) {
        try {
            recognizer.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener(result -> {
                        try { callback.success(result); }
                        catch (RuntimeException error) { callback.failure(error); }
                    })
                    .addOnFailureListener(callback::failure)
                    .addOnCompleteListener(task -> { if (owned) input.recycle(); });
        } catch (RuntimeException error) {
            if (owned) input.recycle();
            callback.failure(error);
        }
    }

    private void recognizeRegion(Bitmap source, ScreenSnapshot screen, VisionImageRegion[] regions,
                                 int index, List<VisionElementMapper.OcrLine> output,
                                 long started, Callback callback) {
        if (index == regions.length) { finish(output, source, screen, started, callback); return; }
        VisionImageRegion region = regions[index];
        Bitmap cropped = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height());
        float scale = Math.max(1f, Math.min(1.5f, 2048f / region.width()));
        Bitmap input = Bitmap.createScaledBitmap(cropped, Math.round(region.width() * scale),
                Math.round(region.height() * scale), true);
        if (cropped != source && cropped != input) cropped.recycle();
        process(input, input != source, new TextCallback() {
            public void success(Text result) {
                if (!VisionSafetyPolicy.assessVisibleText(result.getText()).allowed) {
                    callback.onFailure("sensitive_screen_content", null); return;
                }
                output.addAll(lines(result, region, input.getWidth(), input.getHeight()));
                recognizeRegion(source, screen, regions, index + 1, output, started, callback);
            }
            public void failure(Throwable error) { callback.onFailure("ocr_failed", error); }
        });
    }

    private static List<VisionElementMapper.OcrLine> lines(Text result, VisionImageRegion region,
                                                          int width, int height) {
        List<VisionElementMapper.OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            if (containsPrivateData(block.getText())) continue;
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds == null || containsPrivateData(line.getText())) continue;
                lines.add(new VisionElementMapper.OcrLine(line.getText(), region.toScreen(
                        new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}, width, height),
                        line.getConfidence()));
            }
        }
        return lines;
    }

    private static void finish(List<VisionElementMapper.OcrLine> lines, Bitmap source,
                               ScreenSnapshot screen, long started, Callback callback) {
        callback.onSuccess(VisionElementMapper.map(lines, source.getWidth(), source.getHeight(), screen),
                SystemClock.elapsedRealtime() - started);
    }

    void close() {
        recognizer.close();
    }

    /** Defense in depth: mask PII-like regions even though sensitive screens are blocked earlier. */
    private static void maskPrivateRegions(Bitmap bitmap, ScreenSnapshot screen) {
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        Canvas canvas = new Canvas(bitmap);
        float scaleX = bitmap.getWidth() / (float) Math.max(1, screen.screenWidth);
        float scaleY = bitmap.getHeight() / (float) Math.max(1, screen.screenHeight);
        Map<String, AccessibilityNodeSnapshot> nodesById = new HashMap<>();
        for (AccessibilityNodeSnapshot node : screen.nodes) nodesById.put(node.nodeId, node);
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (!node.sensitive && !containsPrivateData(node.text)
                    && !containsPrivateData(node.contentDescription)) continue;
            AccessibilityNodeSnapshot maskNode = privateContainer(node, nodesById, screen);
            if (maskNode.bounds == null || maskNode.bounds.length != 4) continue;
            canvas.drawRect(maskNode.bounds[0] * scaleX, maskNode.bounds[1] * scaleY,
                    maskNode.bounds[2] * scaleX, maskNode.bounds[3] * scaleY, paint);
        }
    }

    private static AccessibilityNodeSnapshot privateContainer(
            AccessibilityNodeSnapshot node,
            Map<String, AccessibilityNodeSnapshot> nodesById,
            ScreenSnapshot screen
    ) {
        AccessibilityNodeSnapshot selected = node;
        long screenArea = (long) Math.max(1, screen.screenWidth) * Math.max(1, screen.screenHeight);
        for (int depth = 0; depth < 3 && selected.parentId != null; depth++) {
            AccessibilityNodeSnapshot parent = nodesById.get(selected.parentId);
            if (parent == null || parent.bounds == null || parent.bounds.length != 4) break;
            long area = Math.max(0L, (long) parent.bounds[2] - parent.bounds[0])
                    * Math.max(0L, (long) parent.bounds[3] - parent.bounds[1]);
            if (area <= 0 || area > screenArea * 35 / 100) break;
            selected = parent;
        }
        return selected;
    }

    private static boolean containsPrivateData(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return EMAIL.matcher(value).find() || PHONE.matcher(value).find()
                || LONG_NUMBER.matcher(value).find()
                || lower.contains("password") || lower.contains("비밀번호")
                || lower.contains("주민등록번호") || lower.contains("account number")
                || lower.contains("계좌 번호") || lower.contains("card number")
                || lower.contains("카드 번호") || lower.contains("삼성 계정")
                || lower.contains("samsung account") || lower.contains("google account");
    }
}
