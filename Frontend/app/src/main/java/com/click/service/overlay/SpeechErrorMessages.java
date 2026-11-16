package com.click.service.overlay;

import android.speech.SpeechRecognizer;

/** Show device recognition errors without exposing audio content. */
public final class SpeechErrorMessages {
    private SpeechErrorMessages() {}
    public static String message(Throwable error) {
        if (error instanceof DeviceSpeechInput.DeviceError) {
            int code = ((DeviceSpeechInput.DeviceError) error).code;
            if (code == DeviceSpeechInput.DeviceError.ON_DEVICE_UNAVAILABLE)
                return "이 기기에서는 기기 내 음성 인식을 사용할 수 없어요. 글로 입력해 주세요.";
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return "말로 입력하시려면 마이크 사용을 허용한 뒤 다시 눌러 주세요.";
            if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                return "말씀하신 내용을 잘 듣지 못했어요. 마이크를 꾹 누르고 다시 말씀해 주시겠어요?";
            if (code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                return "기기에 한국어 음성 모델이 준비되지 않았어요. 음성 인식 설정에서 한국어 모델을 준비하거나 글로 입력해 주세요.";
            if (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                return "이 기기는 한국어 기기 내 음성 인식을 지원하지 않아요. 글로 입력해 주세요.";
            return "기기 내 음성 인식을 완료하지 못했어요. 마이크를 다시 누르거나 글로 입력해 주세요.";
        }
        if (error instanceof SecurityException) return "말로 입력하시려면 마이크 사용을 허용한 뒤 다시 눌러 주세요.";
        return "음성 입력을 완료하지 못했어요. 마이크를 다시 누르거나 글로 입력해 주세요.";
    }
    public static void log(Throwable error) {
        String reason = error instanceof DeviceSpeechInput.DeviceError
                ? "DEVICE_" + ((DeviceSpeechInput.DeviceError) error).code : error.getClass().getSimpleName();
        android.util.Log.w("ClickSpeech", "Input failed: " + reason);
    }
}
