package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import com.click.data.snapshot.AgentVisionSnapshot;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.VisionOcrElement;
import com.click.data.snapshot.VisionPrivacy;
import com.click.data.snapshot.VisionSafetyPolicy;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Called only once after the user accepts the current BE vision challenge. */
public final class OnDeviceVision {
    public interface Callback {
        void onSuccess(ScreenSnapshot screen, JSONObject snapshot);
        void onFailure(String code, String message);
    }

    private OnDeviceVision() {}

    static void capture(AccessibilityService service, VisionOcrEngine engine,
                        ScreenSnapshot screen, Callback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.onFailure("unsupported", "화면 분석은 Android 11 이상에서 지원해요.");
            return;
        }
        if (!"com.android.settings".equals(screen.packageName)
                && !"com.samsung.android.settings".equals(screen.packageName)) {
            callback.onFailure("unsupported", "현재 화면 분석은 설정 앱에서만 지원해요. 설정 앱을 열어 주세요.");
            return;
        }
        // Password, payment, finance, System UI and keyboard screens are never captured.
        if (!VisionSafetyPolicy.assess(screen).allowed) {
            callback.onFailure("masking_failed", "개인정보가 보일 수 있는 화면이라 화면 분석을 하지 않았어요.");
            return;
        }
        final long capturedAtMs = System.currentTimeMillis();
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Bitmap bitmap = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            bitmap = hardware.copy(Bitmap.Config.ARGB_8888, true);
                            hardware.recycle();
                        }
                    } catch (RuntimeException error) {
                        callback.onFailure("capture_failed", "화면 이미지를 읽지 못했어요. 다시 시도할 때 허용을 확인할게요.");
                        return;
                    }
                    if (bitmap == null) {
                        callback.onFailure("capture_failed", "화면 이미지를 읽지 못했어요.");
                        return;
                    }
                    if (bitmap.getWidth() != screen.screenWidth || bitmap.getHeight() != screen.screenHeight) {
                        bitmap.recycle();
                        callback.onFailure("screen_changed", "화면 크기가 바뀌었어요. 현재 화면에서 다시 요청해 주세요.");
                        return;
                    }
                    // Remove known input fields before local OCR. Only graph labels leave the device.
                    try {
                        Canvas canvas = new Canvas(bitmap);
                        Paint mask = new Paint();
                        mask.setColor(Color.BLACK);
                        if (!screen.privacyMaskingReady) throw new IllegalStateException("Unknown sensitive bounds");
                        for (AccessibilityNodeSnapshot node : screen.nodes) {
                            if (node.sensitiveInput || (node.className != null
                                    && node.className.toLowerCase(java.util.Locale.ROOT).contains("edittext"))) {
                                VisionPrivacy.requireMaskBounds(node.bounds, bitmap.getWidth(), bitmap.getHeight());
                                canvas.drawRect(node.bounds[0], node.bounds[1], node.bounds[2], node.bounds[3], mask);
                            }
                        }
                    } catch (RuntimeException error) {
                        bitmap.recycle();
                        callback.onFailure("masking_failed", "개인정보를 안전하게 가리지 못해 화면 분석을 중단했어요.");
                        return;
                    }
                    recognize(engine, bitmap, screen, capturedAtMs, callback);
                }
                @Override public void onFailure(int errorCode) {
                    callback.onFailure("capture_failed", "화면 캡처를 사용할 수 없어요. 접근성 권한 또는 보호된 화면인지 확인해 주세요.");
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure("capture_failed", "화면 캡처 권한을 사용할 수 없어요. 접근성 서비스를 다시 켜 주세요.");
        }
    }

    private static void recognize(VisionOcrEngine engine, Bitmap bitmap, ScreenSnapshot screen,
                                  long capturedAtMs, Callback callback) {
        try {
            engine.recognize(bitmap, screen, new VisionOcrEngine.Callback() {
                @Override public void onSuccess(List<VisionOcrElement> elements, long processingTimeMs) {
                    bitmap.recycle();
                    try {
                        callback.onSuccess(screen, AgentVisionSnapshot.fromOcr(
                                screen, true, exportable(elements), capturedAtMs, processingTimeMs));
                    } catch (RuntimeException error) {
                        callback.onFailure("masking_failed", "전송할 글자의 개인정보 검사를 완료하지 못해 분석을 중단했어요.");
                    }
                }
                @Override public void onFailure(String reason, Throwable error) {
                    bitmap.recycle();
                    if ("sensitive_screen_content".equals(reason)) {
                        callback.onFailure("masking_failed", "개인정보가 보일 수 있는 화면이라 분석 결과를 버렸어요.");
                    } else if ("bitmap_not_mutable".equals(reason)) {
                        callback.onFailure("capture_failed", "화면 이미지를 읽지 못했어요.");
                    } else {
                        callback.onFailure("ocr_failed", "화면의 글자를 읽지 못했어요. 다시 요청하면 새로 허용을 확인할게요.");
                    }
                }
            });
        } catch (RuntimeException error) {
            bitmap.recycle();
            callback.onFailure("ocr_failed", "화면 분석을 시작하지 못했어요.");
        }
    }

    /** Only allowlisted graph labels leave the device; everything else is dropped by line. */
    private static List<VisionOcrElement> exportable(List<VisionOcrElement> elements) {
        List<VisionOcrElement> output = new ArrayList<>();
        for (VisionOcrElement element : elements) {
            String label = VisionPrivacy.exportLabel(element.text);
            if (label == null) continue;
            boolean title = label.equals("설정") || label.equals("settings");
            output.add(new VisionOcrElement("ocr_" + output.size(), label, element.bounds,
                    element.role, element.confidence, element.actionable && !title));
        }
        return output;
    }
}
