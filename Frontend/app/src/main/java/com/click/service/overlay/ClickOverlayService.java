package com.click.service.overlay;

import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.LatestScreenSnapshot;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.ConfirmationOverlayView;
import com.click.ui.overlay.GuideOverlayView;
import com.click.ui.overlay.TrashDropView;

import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.UUID;

public final class ClickOverlayService extends Service implements ClickOverlayLayout.Callback {
    private static WeakReference<ClickOverlayService> runningService = new WeakReference<>(null);
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private ClickOverlayLayout overlayView;
    private TrashDropView trashDropView;
    private WindowManager.LayoutParams trashDropParams;
    private ValueAnimator snapAnimator;
    private SpeechRecognizer speechRecognizer;
    private Handler speechHandler;
    private boolean listening;
    private boolean overTrashTarget;
    private AgentTurnGateway agentGateway;
    private String agentSessionId;
    private String initialGoal;
    private boolean firstTurnSent;
    private boolean turnInFlight;
    private String lastSnapshotFingerprint;
    private GuideOverlayView guideOverlayView;
    private WindowManager.LayoutParams guideOverlayParams;
    private ConfirmationOverlayView confirmationView;
    private WindowManager.LayoutParams confirmationParams;
    private final BroadcastReceiver collapseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (OverlayActions.ACTION_COLLAPSE.equals(intent.getAction()) && overlayView != null) {
                overlayView.collapse();
            } else if (OverlayActions.ACTION_SNAPSHOT_UPDATED.equals(intent.getAction())) {
                sendAgentTurn(null, false);
            }
        }
    };
    private float dragTargetX;
    private float dragTargetY;
    private boolean dragFrameScheduled;
    private final Runnable dragFrame = () -> {
        dragFrameScheduled = false;
        if (windowManager == null || windowParams == null || overlayView == null) return;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        runningService = new WeakReference<>(this);
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = getSystemService(WindowManager.class);
        overlayView = new ClickOverlayLayout(this);
        overlayView.setCallback(this);
        trashDropView = new TrashDropView(this);
        trashDropParams = new WindowManager.LayoutParams(
                dp(112), dp(112), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        trashDropParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        trashDropParams.y = dp(18);
        speechHandler = new Handler(getMainLooper());
        agentGateway = new AgentTurnGateway();
        prepareSpeechRecognizer();
        IntentFilter overlayFilter = new IntentFilter(OverlayActions.ACTION_COLLAPSE);
        overlayFilter.addAction(OverlayActions.ACTION_SNAPSHOT_UPDATED);
        registerReceiver(collapseReceiver, overlayFilter, Context.RECEIVER_NOT_EXPORTED);
        windowParams = new WindowManager.LayoutParams(
                dp(64), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.START | Gravity.TOP;
        windowParams.x = screenWidth() - dp(64) - dp(12);
        windowParams.y = screenHeight() - dp(64) - dp(48);
        windowManager.addView(overlayView, windowParams);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {
        if (windowManager == null || windowParams == null) return;
        float anchorX = windowParams.x + overlayView.getClickCenterX(oldMode);
        float anchorY = windowParams.y + overlayView.getClickCenterY(oldMode);
        windowParams.width = overlayView.getWindowWidth(newMode, screenWidth());
        windowParams.height = overlayView.getWindowHeight(newMode);
        windowParams.x = Math.round(anchorX - overlayView.getClickCenterX(newMode));
        windowParams.y = Math.round(anchorY - overlayView.getClickCenterY(newMode));
        clampWindowPosition();
        updateFocusableFlag(newMode == ClickOverlayLayout.Mode.TEXT);
        windowManager.updateViewLayout(overlayView, windowParams);
        if (newMode == ClickOverlayLayout.Mode.VOICE) startListening();
        else stopListening();
        if (newMode == ClickOverlayLayout.Mode.COLLAPSED) {
            overlayView.post(this::snapToNearestEdge);
        }
    }

    @Override
    public void onDragStarted() {
        if (snapAnimator != null) snapAnimator.cancel();
        showTrashTarget();
        dragTargetX = windowParams.x;
        dragTargetY = windowParams.y;
    }

    @Override
    public void onDragBy(float deltaX, float deltaY) {
        if (windowManager == null || windowParams == null) return;
        int maxX = Math.max(0, screenWidth() - windowParams.width);
        int maxY = Math.max(0, screenHeight() - windowParams.height);
        dragTargetX = Math.max(0, Math.min(dragTargetX + deltaX, maxX));
        dragTargetY = Math.max(0, Math.min(dragTargetY + deltaY, maxY));
        if (!dragFrameScheduled) {
            dragFrameScheduled = true;
            overlayView.postOnAnimation(dragFrame);
        }
        updateTrashTargetState();
    }

    @Override
    public void onDragEnded() {
        overlayView.removeCallbacks(dragFrame);
        dragFrameScheduled = false;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
        updateTrashTargetState();
        if (overTrashTarget) {
            hideTrashTarget();
            stopSelf();
            return;
        }
        hideTrashTarget();
        snapToNearestEdge();
    }

    @Override
    public void onTextSubmitted(String text) {
        beginAgentSession(text);
    }

    @Override
    public void onCloseRequested() {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (snapAnimator != null) snapAnimator.cancel();
        if (speechHandler != null) speechHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(null);
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        listening = false;
        if (agentGateway != null) agentGateway.close();
        unregisterReceiver(collapseReceiver);
        hideGuideOverlay();
        hideConfirmation();
        hideTrashTarget();
        if (windowManager != null && overlayView != null && overlayView.isAttachedToWindow()) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        trashDropView = null;
        windowManager = null;
        if (runningService.get() == this) runningService.clear();
        super.onDestroy();
    }

    public static void collapseIfRunning() {
        ClickOverlayService service = runningService.get();
        if (service != null && service.overlayView != null) {
            service.overlayView.post(service.overlayView::collapse);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void snapToNearestEdge() {
        if (windowManager == null || windowParams == null) return;
        int maxX = Math.max(0, screenWidth() - windowParams.width);
        int maxY = Math.max(0, screenHeight() - windowParams.height);
        int left = windowParams.x;
        int right = maxX - windowParams.x;
        int top = windowParams.y;
        int bottom = maxY - windowParams.y;
        int nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
        int targetX = windowParams.x;
        int targetY = windowParams.y;
        if (nearest == left) targetX = 0;
        else if (nearest == right) targetX = maxX;
        else if (nearest == top) targetY = 0;
        else targetY = maxY;
        animateWindowTo(targetX, targetY);
    }

    private void showTrashTarget() {
        if (windowManager == null || trashDropView == null || trashDropView.isAttachedToWindow()) return;
        overTrashTarget = false;
        trashDropView.setActive(false);
        windowManager.addView(trashDropView, trashDropParams);
    }

    private void hideTrashTarget() {
        overTrashTarget = false;
        if (windowManager != null && trashDropView != null && trashDropView.isAttachedToWindow()) {
            windowManager.removeView(trashDropView);
        }
    }

    private void updateTrashTargetState() {
        if (trashDropView == null || !trashDropView.isAttachedToWindow() || overlayView == null) return;
        float iconX = dragTargetX + overlayView.getClickCenterX(overlayView.getMode());
        float iconY = dragTargetY + overlayView.getClickCenterY(overlayView.getMode());
        float trashX = screenWidth() / 2f;
        float trashY = screenHeight() - dp(18) - dp(56);
        overTrashTarget = Math.hypot(iconX - trashX, iconY - trashY) <= dp(68);
        trashDropView.setActive(overTrashTarget);
    }

    private void animateWindowTo(int targetX, int targetY) {
        final int startX = windowParams.x;
        final int startY = windowParams.y;
        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(240L);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation -> {
            if (windowManager == null || windowParams == null) return;
            float progress = (float) animation.getAnimatedValue();
            windowParams.x = Math.round(startX + (targetX - startX) * progress);
            windowParams.y = Math.round(startY + (targetY - startY) * progress);
            windowManager.updateViewLayout(overlayView, windowParams);
        });
        snapAnimator.start();
    }

    private void clampWindowPosition() {
        windowParams.x = Math.max(0, Math.min(windowParams.x, screenWidth() - windowParams.width));
        windowParams.y = Math.max(0, Math.min(windowParams.y, screenHeight() - windowParams.height));
    }

    private void updateFocusableFlag(boolean focusable) {
        if (focusable) windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    }

    private void prepareSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                listening = true;
                if (overlayView != null) overlayView.setRecognizedSpeech("듣고 있어요…");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { listening = false; }
            @Override public void onError(int error) {
                listening = false;
                if (overlayView != null && overlayView.getMode() == ClickOverlayLayout.Mode.VOICE) {
                    overlayView.setRecognizedSpeech("다시 말씀해주세요.");
                    speechHandler.postDelayed(ClickOverlayService.this::startListening, 900L);
                }
            }
            @Override public void onResults(Bundle results) {
                listening = false;
                String text = showRecognitionText(results);
                if (text != null && agentSessionId == null) beginAgentSession(text);
                if (overlayView != null && overlayView.getMode() == ClickOverlayLayout.Mode.VOICE) {
                    speechHandler.postDelayed(ClickOverlayService.this::startListening, 1100L);
                }
            }
            @Override public void onPartialResults(Bundle partialResults) { showRecognitionText(partialResults); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        if (speechRecognizer == null || listening || overlayView == null
                || overlayView.getMode() != ClickOverlayLayout.Mode.VOICE) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            overlayView.setRecognizedSpeech("마이크 권한을 허용해주세요.");
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }

    private void stopListening() {
        if (speechHandler != null) speechHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null && listening) speechRecognizer.cancel();
        listening = false;
    }

    private String showRecognitionText(Bundle bundle) {
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty() && overlayView != null) {
            String text = matches.get(0);
            overlayView.setRecognizedSpeech(text);
            return text;
        }
        return null;
    }

    private void beginAgentSession(String goal) {
        if (goal == null || goal.trim().isEmpty()) return;
        agentSessionId = UUID.randomUUID().toString();
        initialGoal = goal.trim();
        firstTurnSent = false;
        lastSnapshotFingerprint = null;
        if (overlayView != null) overlayView.setRecognizedSpeech("화면을 확인하고 있어요…");
        sendAgentTurn(null, true);
    }

    private void sendAgentTurn(String confirmation, boolean force) {
        if (agentSessionId == null || turnInFlight || agentGateway == null) return;
        ScreenSnapshot snapshot = LatestScreenSnapshot.get();
        if (snapshot == null) {
            if (overlayView != null) overlayView.setRecognizedSpeech("접근성 화면 정보를 기다리고 있어요…");
            return;
        }
        String fingerprint = snapshot.fingerprint();
        if (!force && firstTurnSent && confirmation == null && fingerprint.equals(lastSnapshotFingerprint)) return;
        AgentTurnContract.Request request = new AgentTurnContract.Request(
                agentSessionId, firstTurnSent ? null : initialGoal, snapshot, confirmation);
        turnInFlight = true;
        lastSnapshotFingerprint = fingerprint;
        agentGateway.send(request, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                turnInFlight = false;
                firstTurnSent = true;
                renderGuide(response);
                ScreenSnapshot latest = LatestScreenSnapshot.get();
                if (latest != null && !latest.fingerprint().equals(lastSnapshotFingerprint)) {
                    sendAgentTurn(null, false);
                }
            }

            @Override public void onFailure(Throwable error) {
                turnInFlight = false;
                android.util.Log.e("ClickAgent", "Agent turn failed", error);
                if (overlayView != null) overlayView.setRecognizedSpeech("서버 연결을 확인해주세요.");
            }
        });
    }

    private void renderGuide(AgentTurnContract.Response response) {
        AgentTurnContract.GuideCommand command = response.guideCommand;
        if (overlayView != null && command.message != null && !command.message.isEmpty()) {
            overlayView.setRecognizedSpeech(command.message);
        }
        if ("confirmation".equals(command.commandType)) {
            hideGuideOverlay();
            showConfirmation(command.message);
            return;
        }
        hideConfirmation();
        if ("done".equals(command.commandType) || "blocked".equals(command.commandType)) {
            hideGuideOverlay();
            if ("done".equals(command.commandType)) agentSessionId = null;
            return;
        }
        showGuideOverlay(command.targetBounds, command.actionType);
    }

    private void showGuideOverlay(int[] bounds, String actionType) {
        if (bounds == null || windowManager == null) {
            hideGuideOverlay();
            return;
        }
        if (guideOverlayView == null) guideOverlayView = new GuideOverlayView(this);
        guideOverlayView.showTarget(bounds, actionType);
        if (guideOverlayView.isAttachedToWindow()) return;
        guideOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        guideOverlayParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(guideOverlayView, guideOverlayParams);
    }

    private void hideGuideOverlay() {
        if (windowManager != null && guideOverlayView != null && guideOverlayView.isAttachedToWindow()) {
            windowManager.removeView(guideOverlayView);
        }
    }

    private void showConfirmation(String message) {
        hideConfirmation();
        if (windowManager == null) return;
        confirmationView = new ConfirmationOverlayView(this, message, accepted -> {
            hideConfirmation();
            sendAgentTurn(accepted ? "accepted" : "rejected", true);
        });
        confirmationParams = new WindowManager.LayoutParams(
                screenWidth() - dp(28), dp(176), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        confirmationParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        confirmationParams.y = dp(28);
        windowManager.addView(confirmationView, confirmationParams);
    }

    private void hideConfirmation() {
        if (windowManager != null && confirmationView != null && confirmationView.isAttachedToWindow()) {
            windowManager.removeView(confirmationView);
        }
        confirmationView = null;
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
