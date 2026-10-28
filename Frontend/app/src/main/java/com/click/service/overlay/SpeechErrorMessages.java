package com.click.service.overlay;

import android.speech.SpeechRecognizer;
import com.click.data.remote.ApiException;
import org.json.JSONObject;

/** Show actionable causes without exposing server response bodies or audio content. */
public final class SpeechErrorMessages {
    private SpeechErrorMessages() {}
    public static String message(Throwable error) {
        if (error instanceof DeviceSpeechInput.DeviceError) {
            int code = ((DeviceSpeechInput.DeviceError) error).code;
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return "마이크 권한을 허용한 뒤 다시 눌러주세요.";
            if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                return "말소리를 듣지 못했어요. 마이크를 다시 누르고 천천히 말씀해 주세요.";
            return "기기 음성 인식에 연결하지 못했어요. 마이크를 다시 누르면 다른 방식으로 입력해요.";
        }
        if (error instanceof ApiException) {
            int status = ((ApiException) error).status;
            if (isNoSpeech((ApiException) error)) return "말소리를 듣지 못했어요. 마이크를 다시 누르고 천천히 말씀해 주세요.";
            if (status == 401 || status == 403) return "음성 입력 연결을 위해 다시 로그인해 주세요.";
            if (status == 503 || status == 502 || status == 504) return "음성 변환 서버에 연결하지 못했어요. 잠시 후 마이크를 다시 눌러주세요.";
        }
        if ("PRIVATE_STT_UNAVAILABLE".equals(error.getMessage())) return "음성 입력 서버가 준비되지 않았어요. 지금은 글로 입력해 주세요.";
        if ("AUTH_REQUIRED".equals(error.getMessage())) return "음성 입력 연결을 위해 다시 로그인해 주세요.";
        if (error instanceof SecurityException) return "마이크 권한을 허용한 뒤 다시 눌러주세요.";
        if (error instanceof java.net.SocketTimeoutException) return "음성 변환 응답이 늦어지고 있어요. 연결을 확인하고 다시 눌러주세요.";
        if ("NO_SPEECH".equals(error.getMessage())) return "말소리를 듣지 못했어요. 마이크를 다시 누르고 천천히 말씀해 주세요.";
        return "음성 입력을 완료하지 못했어요. 연결을 확인하고 마이크를 다시 눌러주세요.";
    }
    private static boolean isNoSpeech(ApiException error) {
        if (error.status != 422 || error.responseBody == null || error.responseBody.length() > 4096) return false;
        try {
            JSONObject detail = new JSONObject(error.responseBody).optJSONObject("error");
            return detail != null && "NO_SPEECH".equals(detail.optString("code"));
        } catch (org.json.JSONException ignored) {
            return false;
        }
    }
    public static void log(Throwable error) {
        String reason = error instanceof ApiException ? (isNoSpeech((ApiException) error) ? "NO_SPEECH" : "HTTP_" + ((ApiException) error).status)
                : error instanceof DeviceSpeechInput.DeviceError ? "DEVICE_" + ((DeviceSpeechInput.DeviceError) error).code
                : "PRIVATE_STT_UNAVAILABLE".equals(error.getMessage()) ? "PRIVATE_STT_UNAVAILABLE"
                : "AUTH_REQUIRED".equals(error.getMessage()) ? "AUTH_REQUIRED"
                : "NO_SPEECH".equals(error.getMessage()) ? "NO_SPEECH" : error.getClass().getSimpleName();
        android.util.Log.w("ClickSpeech", "Input failed: " + reason);
    }
}
