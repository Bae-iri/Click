package com.click.ui.tutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.click.ui.overlay.ClickIconRenderer;

public final class GalaxyTutorialView extends View {
    public enum Step {
        VOICE_REQUEST, SETTINGS, SETTINGS_SCROLL, GENERAL,
        GENERAL_SCROLL, KEYBOARD, KEYBOARD_SCROLL, FONT_SIZE,
        SLIDER, CONFIRM, DONE
    }

    public interface Listener {
        void onMicrophoneRequested();
        void onStepChanged(Step step);
        void onSliderCompleted();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF phone = new RectF();
    private final RectF target = new RectF();
    private Step step = Step.VOICE_REQUEST;
    private Listener listener;
    private float autoMoveProgress;
    private float settingsScrollProgress;
    private ValueAnimator settingsScrollAnimator;
    private float sliderProgress = .42f;
    private boolean draggingSlider;
    private String voiceTranscript = "말씀해주세요…";

    public GalaxyTutorialView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(238, 240, 245));
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public Step getStep() { return step; }

    public void setVoiceTranscript(String text) {
        voiceTranscript = text == null || text.trim().isEmpty() ? "말씀해주세요…" : text.trim();
        invalidate();
    }

    public void showRecognizedRequest() {
        step = Step.SETTINGS;
        autoMoveProgress = 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(750L);
        animator.addUpdateListener(value -> {
            autoMoveProgress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
        notifyStep();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        // Leave breathing room around the simulated phone so its status bar and page title
        // are not clipped on short screens or devices using a large system font scale.
        float availableHeight = (getHeight() - dp(32)) * .86f;
        float phoneWidth = Math.min(getWidth() - dp(8), availableHeight * 9f / 19.5f);
        float phoneHeight = Math.min(availableHeight, phoneWidth * 19.5f / 9f);
        float left = (getWidth() - phoneWidth) / 2f;
        float top = (getHeight() - phoneHeight) / 2f;
        phone.set(left, top, left + phoneWidth, top + phoneHeight);
        paint.setColor(Color.rgb(20, 22, 27));
        canvas.drawRoundRect(phone, dp(28), dp(28), paint);
        RectF screen = new RectF(phone.left + dp(7), phone.top + dp(7), phone.right - dp(7), phone.bottom - dp(7));
        paint.setColor(Color.rgb(250, 250, 252));
        canvas.drawRoundRect(screen, dp(23), dp(23), paint);
        drawStatusBar(canvas, screen);

        canvas.save();
        canvas.clipRect(screen);
        switch (step) {
            case VOICE_REQUEST:
            case SETTINGS:
                drawGalaxyHome(canvas, screen);
                break;
            case SETTINGS_SCROLL:
                drawSamsungSettings(canvas, screen, settingsScrollProgress, false);
                break;
            case GENERAL:
                drawSamsungSettings(canvas, screen, 1f, true);
                break;
            case GENERAL_SCROLL:
                drawGeneralSettings(canvas, screen, settingsScrollProgress, false);
                break;
            case KEYBOARD:
                drawGeneralSettings(canvas, screen, 1f, true);
                break;
            case KEYBOARD_SCROLL:
                drawKeyboardSettings(canvas, screen, settingsScrollProgress, false);
                break;
            case FONT_SIZE:
                drawKeyboardSettings(canvas, screen, 1f, true);
                break;
            case SLIDER:
            case CONFIRM:
            case DONE:
                drawFontSizeScreen(canvas, screen);
                break;
        }
        if (step != Step.VOICE_REQUEST && step != Step.SETTINGS_SCROLL
                && step != Step.GENERAL_SCROLL && step != Step.KEYBOARD_SCROLL
                && step != Step.CONFIRM && step != Step.DONE) drawPointer(canvas);
        canvas.restore();
    }

    private void drawStatusBar(Canvas canvas, RectF screen) {
        paint.setColor(Color.rgb(40, 44, 50));
        paint.setTextSize(dp(10));
        canvas.drawText("6:49", screen.left + dp(14), screen.top + dp(18), paint);
        canvas.drawText("●  Wi-Fi   60%", screen.right - dp(90), screen.top + dp(18), paint);
    }

    private void drawGalaxyHome(Canvas canvas, RectF screen) {
        paint.setShader(new android.graphics.LinearGradient(
                screen.left, screen.top, screen.right, screen.bottom,
                0xffd9e8f7, 0xfff3e5e9, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, dp(23), dp(23), paint);
        paint.setShader(null);
        drawStatusBar(canvas, screen);
        float shift = step == Step.SETTINGS ? (1f - autoMoveProgress) * dp(38) : 0;
        String[] labels = {"전화", "메시지", "카메라", "갤러리", "인터넷", "설정"};
        int[] colors = {0xff58b86b, 0xff64a9ef, 0xffff6680, 0xffffa84e, 0xff8178e8, 0xff9aa0a9};
        float startY = screen.top + dp(92);
        for (int i = 0; i < labels.length; i++) {
            int column = i % 3;
            int row = i / 3;
            float columnGap = (screen.width() - dp(78)) / 2f;
            float cx = screen.left + dp(39) + column * columnGap + shift;
            float cy = startY + row * dp(98);
            paint.setColor(colors[i]);
            canvas.drawRoundRect(new RectF(cx - dp(25), cy - dp(25), cx + dp(25), cy + dp(25)), dp(13), dp(13), paint);
            paint.setColor(Color.DKGRAY);
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(labels[i], cx, cy + dp(43), paint);
            if (labels[i].equals("설정")) target.set(cx - dp(31), cy - dp(31), cx + dp(31), cy + dp(50));
        }
        paint.setTextAlign(Paint.Align.LEFT);
        if (step == Step.VOICE_REQUEST) drawVoiceOverlay(canvas, screen);
        drawHomeDots(canvas, screen);
    }

    private void drawVoiceOverlay(Canvas canvas, RectF screen) {
        float centerY = screen.bottom - dp(48);
        float controlsWidth = Math.min(screen.width() - dp(28), dp(260));
        RectF controls = new RectF(screen.centerX() - controlsWidth / 2f, centerY - dp(29),
                screen.centerX() + controlsWidth / 2f, centerY + dp(29));
        paint.setColor(0xf8ffffff);
        paint.setShadowLayer(dp(7), 0, dp(3), 0x44202a38);
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(controls, dp(30), dp(30), paint);
        paint.clearShadowLayer();

        float clickX = controls.right - dp(88);
        float micX = controls.right - dp(30);
        drawClickOverlayIcon(canvas, clickX, centerY, dp(29), 0f, false);
        drawGreenMicrophone(canvas, micX, centerY);
        target.set(micX - dp(27), centerY - dp(27), micX + dp(27), centerY + dp(27));

        RectF bubble = new RectF(controls.left + dp(4), centerY - dp(23), controls.right - dp(122), centerY + dp(23));
        paint.setColor(0xfff5f7fa);
        canvas.drawRoundRect(bubble, dp(15), dp(15), paint);
        paint.setColor(0xff2b3039);
        paint.setTextSize(dp(11));
        int length = paint.breakText(voiceTranscript, true, bubble.width() - dp(12), null);
        String shown = voiceTranscript.substring(0, Math.min(length, voiceTranscript.length()));
        canvas.drawText(shown, bubble.left + dp(6), bubble.centerY() + dp(4), paint);
    }

    private void drawGreenMicrophone(Canvas canvas, float cx, float cy) {
        paint.setColor(0xff8fd14f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(new RectF(cx - dp(6), cy - dp(13), cx + dp(6), cy + dp(6)), dp(6), dp(6), paint);
        canvas.drawArc(new RectF(cx - dp(12), cy - dp(3), cx + dp(12), cy + dp(12)), 0, 180, false, paint);
        canvas.drawLine(cx, cy + dp(12), cx, cy + dp(18), paint);
        canvas.drawLine(cx - dp(6), cy + dp(18), cx + dp(6), cy + dp(18), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSamsungSettings(Canvas canvas, RectF screen, float progress, boolean highlightGeneral) {
        paint.setColor(0xfff4f4f6);
        canvas.drawRect(screen.left, screen.top + dp(24), screen.right, screen.bottom, paint);
        paint.setColor(0xff202124);
        paint.setTextSize(dp(27));
        paint.setFakeBoldText(true);
        canvas.drawText("설정", screen.left + dp(22), screen.top + dp(72), paint);
        paint.setFakeBoldText(false);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(screen.right - dp(31), screen.top + dp(61), dp(19), paint);
        paint.setColor(0xff55585f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawCircle(screen.right - dp(33), screen.top + dp(59), dp(6), paint);
        canvas.drawLine(screen.right - dp(29), screen.top + dp(64), screen.right - dp(24), screen.top + dp(69), paint);
        paint.setStyle(Paint.Style.FILL);

        String[] rows = {
                "연결", "소리 및 진동", "알림", "디스플레이", "배경화면 및 스타일", "테마",
                "홈 화면", "잠금화면 및 AOD", "보안 및 개인정보 보호", "위치", "안전 및 긴급",
                "계정 및 백업", "Google", "유용한 기능", "디지털 웰빙", "배터리", "애플리케이션", "일반"
        };
        int[] iconColors = {
                0xff4e8df5, 0xff8c6be8, 0xffff6f74, 0xff51a9ef, 0xffe764b7, 0xff8a72e8,
                0xff4aa7ef, 0xff696fd6, 0xff4a9b73, 0xff5d9cef, 0xffff735f, 0xff5595ef,
                0xff4c8cf5, 0xffe08b3f, 0xff6d90d8, 0xff55a27b, 0xff7b76e8, 0xff7a8a9b
        };
        float contentTop = screen.top + dp(96);
        float maxOffset = dp(620);
        float offset = maxOffset * progress;
        RectF group = new RectF(screen.left + dp(12), contentTop, screen.right - dp(12), screen.bottom + maxOffset);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(group, dp(20), dp(20), paint);
        canvas.save();
        canvas.clipRect(screen.left, contentTop, screen.right, screen.bottom);
        for (int i = 0; i < rows.length; i++) {
            float rowTop = contentTop + i * dp(54) - offset;
            if (rowTop < contentTop - dp(54) || rowTop > screen.bottom) continue;
            paint.setColor(iconColors[i]);
            canvas.drawCircle(screen.left + dp(39), rowTop + dp(27), dp(13), paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(i == rows.length - 1 ? "A" : "•", screen.left + dp(39), rowTop + dp(31), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(0xff24262b);
            paint.setTextSize(dp(15));
            canvas.drawText(rows[i], screen.left + dp(63), rowTop + dp(32), paint);
            if (i < rows.length - 1) {
                paint.setColor(0xffeeeeef);
                canvas.drawRect(screen.left + dp(62), rowTop + dp(53), screen.right - dp(24), rowTop + dp(54), paint);
            }
            if (i == rows.length - 1 && highlightGeneral) {
                target.set(screen.left + dp(18), rowTop + dp(3), screen.right - dp(18), rowTop + dp(51));
            }
        }
        canvas.restore();
    }

    private void drawGeneralSettings(Canvas canvas, RectF screen, float progress, boolean highlightKeyboard) {
        String[] rows = {
                "언어", "앱 언어", "글자 읽어주기", "음성 입력", "날짜 및 시간",
                "삼성 키보드 설정", "기본 키보드 및 추가 키보드 설정", "하드웨어 키보드",
                "마우스 및 트랙패드", "비밀번호 및 자동 입력", "초기화", "맞춤형 서비스"
        };
        String[] subtitles = {
                "한국어(대한민국)", "", "", "", "", "English (US), 한국어",
                "", "연결 안 됨", "", "", "", "사용 중"
        };
        drawOneUiDetailList(canvas, screen, "일반", rows, subtitles, progress, dp(155), 5, highlightKeyboard);
    }

    private void drawKeyboardSettings(Canvas canvas, RectF screen, float progress, boolean highlightFontSize) {
        String[] rows = {
                "문구 대체", "문구 추천", "단축어", "기타 입력 옵션", "키보드 툴바",
                "고대비 키보드", "테마", "모드", "크기와 투명도", "레이아웃",
                "글자 크기", "기호 직접 설정", "음성 입력", "제스처 및 피드백", "손글씨"
        };
        String[] subtitles = {
                "", "", "", "스페이스 키 제스처", "", "", "키패드 있고 밝게",
                "일반 키보드, 분할 키보드", "", "", "", "", "", "", ""
        };
        drawOneUiDetailList(canvas, screen, "삼성 키보드", rows, subtitles,
                progress, dp(365), 10, highlightFontSize);
    }

    private void drawOneUiDetailList(
            Canvas canvas,
            RectF screen,
            String title,
            String[] rows,
            String[] subtitles,
            float progress,
            float maxOffset,
            int targetIndex,
            boolean highlightTarget
    ) {
        paint.setColor(0xfffafafa);
        canvas.drawRect(screen.left, screen.top + dp(24), screen.right, screen.bottom, paint);
        paint.setColor(0xff202124);
        paint.setTextSize(dp(21));
        paint.setFakeBoldText(true);
        canvas.drawText("‹", screen.left + dp(16), screen.top + dp(59), paint);
        canvas.drawText(title, screen.left + dp(45), screen.top + dp(59), paint);
        paint.setFakeBoldText(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawCircle(screen.right - dp(29), screen.top + dp(52), dp(6), paint);
        canvas.drawLine(screen.right - dp(25), screen.top + dp(57), screen.right - dp(21), screen.top + dp(61), paint);
        paint.setStyle(Paint.Style.FILL);

        float listTop = screen.top + dp(76);
        float offset = maxOffset * progress;
        canvas.save();
        canvas.clipRect(screen.left, listTop, screen.right, screen.bottom);
        for (int i = 0; i < rows.length; i++) {
            boolean hasSubtitle = subtitles[i] != null && !subtitles[i].isEmpty();
            float rowHeight = hasSubtitle ? dp(59) : dp(48);
            float accumulated = 0;
            for (int prior = 0; prior < i; prior++) {
                accumulated += subtitles[prior] != null && !subtitles[prior].isEmpty() ? dp(59) : dp(48);
            }
            float rowTop = listTop + accumulated - offset;
            if (rowTop + rowHeight < listTop || rowTop > screen.bottom) continue;
            paint.setColor(0xff202124);
            paint.setTextSize(dp(14));
            canvas.drawText(rows[i], screen.left + dp(22), rowTop + (hasSubtitle ? dp(23) : dp(29)), paint);
            if (hasSubtitle) {
                paint.setColor(0xff5c7faa);
                paint.setTextSize(dp(10));
                canvas.drawText(subtitles[i], screen.left + dp(22), rowTop + dp(41), paint);
            }
            paint.setColor(0xffe8e8e9);
            canvas.drawRect(screen.left + dp(18), rowTop + rowHeight - dp(1),
                    screen.right - dp(18), rowTop + rowHeight, paint);
            if (i == targetIndex && highlightTarget) {
                target.set(screen.left + dp(12), rowTop, screen.right - dp(12), rowTop + rowHeight);
            }
        }
        canvas.restore();
    }

    private void drawHomeDots(Canvas canvas, RectF screen) {
        float y = screen.bottom - dp(90);
        for (int i = 0; i < 3; i++) {
            paint.setColor(i == (step == Step.SETTINGS ? 1 : 0) ? 0xff4f5663 : 0xffc6c9cf);
            canvas.drawCircle(screen.centerX() + (i - 1) * dp(14), y, dp(3), paint);
        }
    }

    private void drawFontSizeScreen(Canvas canvas, RectF screen) {
        paint.setColor(0xff20242b);
        paint.setTextSize(dp(22));
        paint.setFakeBoldText(true);
        canvas.drawText("‹  글자 크기", screen.left + dp(20), screen.top + dp(58), paint);
        paint.setFakeBoldText(false);
        RectF toolbar = new RectF(screen.left + dp(12), screen.top + dp(92), screen.right - dp(12), screen.top + dp(160));
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(toolbar, dp(17), dp(17), paint);
        float lineLeft = toolbar.left + dp(47);
        float lineRight = toolbar.right - dp(47);
        float y = toolbar.centerY();
        paint.setColor(0xffa8aaae);
        paint.setStrokeWidth(dp(3));
        canvas.drawLine(lineLeft, y, lineRight, y, paint);
        paint.setColor(0xff1689ef);
        float knobX = lineLeft + (lineRight - lineLeft) * sliderProgress;
        canvas.drawCircle(knobX, y, dp(10), paint);
        paint.setColor(0xffff3b4d);
        paint.setTextSize(dp(21));
        canvas.drawText("−", toolbar.left + dp(20), y + dp(7), paint);
        paint.setColor(0xff16a05d);
        canvas.drawText("+", toolbar.right - dp(27), y + dp(7), paint);
        target.set(toolbar);
        drawSamsungKeyboard(canvas, screen);
    }

    private void drawSamsungKeyboard(Canvas canvas, RectF screen) {
        float keyboardTop = screen.bottom - dp(185);
        paint.setColor(0xffeceef1);
        canvas.drawRect(screen.left, keyboardTop, screen.right, screen.bottom, paint);
        String[][] keys = {
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"},
                {"ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"},
                {"ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ"}
        };
        float rowTop = keyboardTop + dp(30);
        for (int row = 0; row < keys.length; row++) {
            int count = keys[row].length;
            float gap = dp(3);
            float keyWidth = (screen.width() - dp(14) - gap * (count - 1)) / count;
            float startX = screen.left + dp(7);
            for (int column = 0; column < count; column++) {
                RectF key = new RectF(startX + column * (keyWidth + gap), rowTop + row * dp(36),
                        startX + column * (keyWidth + gap) + keyWidth, rowTop + row * dp(36) + dp(31));
                paint.setColor(Color.WHITE);
                canvas.drawRoundRect(key, dp(4), dp(4), paint);
                paint.setColor(0xff202124);
                paint.setTextSize(dp(12));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(keys[row][column], key.centerX(), key.centerY() + dp(4), paint);
            }
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPointer(Canvas canvas) {
        paint.setColor(0x35ffd84d);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(target, dp(12), dp(12), paint);
        paint.setColor(0xffffc928);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        canvas.drawRoundRect(target, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);
        boolean targetOnRight = target.centerX() > getWidth() / 2f;
        float iconX = targetOnRight
                ? Math.max(phone.left + dp(34), target.left - dp(43))
                : Math.min(phone.right - dp(34), target.right + dp(43));
        float iconY = Math.max(phone.top + dp(40), Math.min(phone.bottom - dp(40), target.centerY() - dp(34)));
        float angle = (float) Math.toDegrees(Math.atan2(target.centerY() - iconY, target.centerX() - iconX));
        drawClickOverlayIcon(canvas, iconX, iconY, dp(27), angle, true);
    }

    private void drawClickOverlayIcon(Canvas canvas, float cx, float cy, float radius, float angle, boolean directional) {
        ClickIconRenderer.draw(this, canvas, paint, arrow, cx, cy, radius, angle, directional);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (step == Step.SLIDER) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && target.contains(event.getX(), event.getY())) {
                draggingSlider = true;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && draggingSlider) {
                sliderProgress = Math.max(.12f, Math.min(.9f, (event.getX() - target.left) / target.width()));
                invalidate();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP && draggingSlider) {
                draggingSlider = false;
                if (sliderProgress > .55f) {
                    step = Step.CONFIRM;
                    notifyStep();
                    if (listener != null) listener.onSliderCompleted();
                }
                return true;
            }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && target.contains(event.getX(), event.getY())) {
            if (step == Step.VOICE_REQUEST) {
                if (listener != null) listener.onMicrophoneRequested();
            } else {
                advance();
            }
            return true;
        }
        return true;
    }

    public void finishTutorial() {
        step = Step.DONE;
        invalidate();
        notifyStep();
    }

    public void resumeSlider() {
        step = Step.SLIDER;
        invalidate();
        notifyStep();
    }

    private void advance() {
        if (step == Step.SETTINGS) {
            startAutomaticScroll(Step.SETTINGS_SCROLL, Step.GENERAL);
            return;
        }
        else if (step == Step.GENERAL) {
            startAutomaticScroll(Step.GENERAL_SCROLL, Step.KEYBOARD);
            return;
        }
        else if (step == Step.KEYBOARD) {
            startAutomaticScroll(Step.KEYBOARD_SCROLL, Step.FONT_SIZE);
            return;
        }
        else if (step == Step.FONT_SIZE) step = Step.SLIDER;
        invalidate();
        notifyStep();
    }

    private void startAutomaticScroll(Step scrollingStep, Step completedStep) {
        step = scrollingStep;
        settingsScrollProgress = 0f;
        notifyStep();
        settingsScrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        settingsScrollAnimator.setStartDelay(350L);
        settingsScrollAnimator.setDuration(1250L);
        settingsScrollAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        settingsScrollAnimator.addUpdateListener(value -> {
            settingsScrollProgress = (float) value.getAnimatedValue();
            invalidate();
        });
        settingsScrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (step != scrollingStep) return;
                step = completedStep;
                invalidate();
                notifyStep();
            }
        });
        settingsScrollAnimator.start();
        invalidate();
    }

    public void goBackOneStep() {
        if (settingsScrollAnimator != null) settingsScrollAnimator.cancel();
        if (step == Step.SETTINGS || step == Step.SETTINGS_SCROLL) step = Step.VOICE_REQUEST;
        else if (step == Step.GENERAL) step = Step.SETTINGS;
        else if (step == Step.GENERAL_SCROLL || step == Step.KEYBOARD) step = Step.GENERAL;
        else if (step == Step.KEYBOARD_SCROLL || step == Step.FONT_SIZE) step = Step.KEYBOARD;
        else if (step == Step.SLIDER || step == Step.CONFIRM) step = Step.FONT_SIZE;
        else return;
        invalidate();
        notifyStep();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (settingsScrollAnimator != null) settingsScrollAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private void notifyStep() {
        if (listener != null) listener.onStepChanged(step);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
