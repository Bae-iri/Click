package com.click;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.click.core.permission.PermissionStatus;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickIconRenderer;

/** A device-independent, image-style walkthrough shown before opening Android Settings. */
public final class AccessibilityPermissionTutorialActivity extends AppCompatActivity {
    private static final String[] INSTRUCTIONS = {
            "접근성 화면에서 ‘설치된 앱’을 눌러주세요.",
            "설치된 앱 목록에서 ‘Click 화면 안내’를 눌러주세요.",
            "‘사용 안 함’ 오른쪽의 스위치를 눌러주세요.",
            "권한 안내가 나타나면 ‘허용’을 눌러주세요.",
            "‘Click 화면 안내 바로가기’가 켜졌는지 확인하면 완료입니다."
    };

    private AccessibilityStepsView preview;
    private TextView instruction;
    private TextView progress;
    private Button previous;
    private Button next;
    private int step;
    private boolean settingsOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = ClickUi.page(this);
        page.setPadding(ClickUi.dp(this, 16), ClickUi.dp(this, 16), ClickUi.dp(this, 16), ClickUi.dp(this, 16));
        page.addView(ClickUi.title(this, "접근성 권한 설정 방법"));

        progress = ClickUi.body(this, "");
        progress.setGravity(Gravity.CENTER);
        progress.setTextColor(ClickUi.RED);
        page.addView(progress, ClickUi.fullWidth(this, 34, 4));

        instruction = ClickUi.body(this, "");
        instruction.setTextSize(18);
        instruction.setGravity(Gravity.CENTER);
        page.addView(instruction, ClickUi.fullWidth(this, 62, 4));

        preview = new AccessibilityStepsView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.setMargins(0, ClickUi.dp(this, 8), 0, ClickUi.dp(this, 12));
        page.addView(preview, previewParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        previous = ClickUi.secondaryButton(this, "이전");
        next = ClickUi.primaryButton(this, "다음");
        previous.setOnClickListener(view -> {
            if (step > 0) {
                step--;
                renderStep();
            }
        });
        next.setOnClickListener(view -> {
            if (step < INSTRUCTIONS.length - 1) {
                step++;
                renderStep();
            } else {
                settingsOpened = true;
                new AccessibilityOnboardingState(this).begin();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        controls.addView(previous, new LinearLayout.LayoutParams(0, ClickUi.dp(this, 58), 1f));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, ClickUi.dp(this, 58), 1f);
        nextParams.leftMargin = ClickUi.dp(this, 10);
        controls.addView(next, nextParams);
        page.addView(controls, ClickUi.fullWidth(this, 58, 0));
        setContentView(page);
        renderStep();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settingsOpened && PermissionStatus.isAccessibilityEnabled(this)) {
            AccessibilityOnboardingState state = new AccessibilityOnboardingState(this);
            if (state.wasTutorialLaunched()) {
                finish();
                return;
            }
            state.markTutorialLaunched();
            Intent tutorial = new Intent(this, TutorialActivity.class);
            tutorial.putExtra(TutorialActivity.EXTRA_FROM_SIGN_UP, true);
            startActivity(tutorial);
            finish();
        }
    }

    private void renderStep() {
        progress.setText((step + 1) + " / " + INSTRUCTIONS.length);
        instruction.setText(INSTRUCTIONS[step]);
        previous.setEnabled(step > 0);
        previous.setAlpha(step > 0 ? 1f : .45f);
        next.setText(step == INSTRUCTIONS.length - 1 ? "접근성 설정 열기" : "다음");
        preview.setStep(step);
        preview.setContentDescription(INSTRUCTIONS[step]);
    }

    private static final class AccessibilityStepsView extends View {
        private static final int[] SCREENSHOTS = {
                R.drawable.accessibility_step_1,
                R.drawable.accessibility_step_2,
                R.drawable.accessibility_step_3,
                R.drawable.accessibility_step_4,
                R.drawable.accessibility_step_5
        };
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int step;
        private Bitmap screenshot;

        AccessibilityStepsView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setStep(int step) {
            this.step = step;
            screenshot = BitmapFactory.decodeResource(getResources(), SCREENSHOTS[step]);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (screenshot == null) screenshot = BitmapFactory.decodeResource(getResources(), SCREENSHOTS[step]);
            float scale = Math.min((float) getWidth() / screenshot.getWidth(),
                    (float) getHeight() / screenshot.getHeight());
            float imageWidth = screenshot.getWidth() * scale;
            float imageHeight = screenshot.getHeight() * scale;
            RectF image = new RectF((getWidth() - imageWidth) / 2f, (getHeight() - imageHeight) / 2f,
                    (getWidth() + imageWidth) / 2f, (getHeight() + imageHeight) / 2f);
            canvas.drawBitmap(screenshot, null, image, paint);
            float[][] targets = {
                    {.03f, .62f, .97f, .78f},
                    {.03f, .12f, .97f, .23f},
                    {.70f, .13f, .95f, .22f},
                    {.70f, .84f, .94f, .94f},
                    {.69f, .22f, .95f, .31f}
            };
            float[] point = targets[step];
            RectF target = new RectF(image.left + image.width() * point[0],
                    image.top + image.height() * point[1], image.left + image.width() * point[2],
                    image.top + image.height() * point[3]);
            highlightAndArrow(canvas, target);
        }

        private String title() {
            if (step == 0) return "접근성";
            if (step == 1) return "설치된 앱";
            return "Click 화면 안내";
        }

        private void row(Canvas canvas, String label, float centerY, boolean strong) {
            float w = getWidth();
            float h = getHeight();
            RectF bounds = rowBounds(w, h, centerY);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(bounds, dp(18), dp(18), paint);
            paint.setColor(strong ? Color.BLACK : Color.rgb(45, 45, 48));
            paint.setTextSize(dp(strong ? 19 : 17));
            paint.setFakeBoldText(strong);
            canvas.drawText(label, bounds.left + dp(22), bounds.centerY() + dp(7), paint);
            paint.setFakeBoldText(false);
        }

        private RectF rowBounds(float w, float h, float centerY) {
            return new RectF(w * .06f, h * centerY - dp(31), w * .94f, h * centerY + dp(31));
        }

        private void smallText(Canvas canvas, String text, float y, int color) {
            paint.setColor(color);
            paint.setTextSize(dp(14));
            paint.setFakeBoldText(false);
            canvas.drawText(text, getWidth() * .12f, getHeight() * y, paint);
        }

        private void drawSwitch(Canvas canvas, float x, float y, boolean enabled) {
            paint.setColor(enabled ? Color.rgb(0, 125, 245) : Color.GRAY);
            RectF track = new RectF(x - dp(30), y - dp(15), x + dp(30), y + dp(15));
            canvas.drawRoundRect(track, dp(15), dp(15), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x + (enabled ? dp(15) : -dp(15)), y, dp(13), paint);
        }

        private void highlightAndArrow(Canvas canvas, RectF target) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x26FF3B4D);
            canvas.drawRoundRect(target, dp(16), dp(16), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(ClickUi.RED);
            canvas.drawRoundRect(target, dp(16), dp(16), paint);
            paint.setStyle(Paint.Style.FILL);

            ClickIconRenderer.draw(this, canvas, paint, new Path(),
                    target.right - dp(14), target.bottom + dp(16), dp(24), 0f, false);
        }

        private float dp(int value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
