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
    private final TextRecognizer recognizer = TextRecognition.getClient(
            new KoreanTextRecognizerOptions.Builder().build());

    interface Callback {
        void onSuccess(List<VisionOcrElement> elements, long processingTimeMs);
        void onFailure(String reason, Throwable error);
    }

    void recognize(Bitmap source, ScreenSnapshot screen, Callback callback) {
        long started = SystemClock.elapsedRealtime();
        if (!source.isMutable()) {
            callback.onFailure("bitmap_not_mutable", null);
            return;
        }
        // The screenshot bitmap is already a private transient copy. Redacting it in place
        // avoids a second full-screen allocation and shortens the fallback pause.
        maskPrivateRegions(source, screen);
        recognizer.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener(result -> {
                    VisionSafetyPolicy.Decision safety =
                            VisionSafetyPolicy.assessVisibleText(result.getText());
                    if (!safety.allowed) {
                        callback.onFailure(safety.reason, null);
                        return;
                    }
                    List<VisionElementMapper.OcrLine> lines = new ArrayList<>();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        // Remove the whole block so an account card cannot leak a person's
                        // name merely because the email address was on a neighboring line.
                        if (containsPrivateData(block.getText())) continue;
                        for (Text.Line line : block.getLines()) {
                            Rect bounds = line.getBoundingBox();
                            if (bounds == null || containsPrivateData(line.getText())) continue;
                            lines.add(new VisionElementMapper.OcrLine(
                                    line.getText(),
                                    new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom},
                                    line.getConfidence()));
                        }
                    }
                    callback.onSuccess(VisionElementMapper.map(lines,
                                    source.getWidth(), source.getHeight(), screen),
                            SystemClock.elapsedRealtime() - started);
                })
                .addOnFailureListener(error -> callback.onFailure("ocr_failed", error));
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
