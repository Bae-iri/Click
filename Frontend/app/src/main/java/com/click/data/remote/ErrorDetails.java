package com.click.data.remote;

import com.click.BuildConfig;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class ErrorDetails {
    private ErrorDetails() {}

    /** Shared by typed and spoken requests; diagnostic details belong in the app log. */
    public static String requestMessage(Throwable error) {
        if (error instanceof com.click.data.snapshot.SettingsScreenPrivacy.Incomplete)
            return "현재 설정 화면을 아직 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.";
        if (error instanceof com.click.data.snapshot.SettingsScreenPrivacy.Blocked) {
            switch (((com.click.data.snapshot.SettingsScreenPrivacy.Blocked) error).reason) {
                case SENSITIVE_INPUT:
                    return "비밀번호 등 입력 정보가 있어 화면 전송을 멈췄어요. 이 단계는 직접 진행해 주세요.";
                case SENSITIVE_SCREEN:
                    return "계정·인증 등 보호가 필요한 화면이에요. 이 단계는 직접 진행해 주세요.";
                case UNSUPPORTED_APP:
                    return "현재 앱 화면은 안내 대상이 아니에요. 휴대폰 설정 화면을 열어주세요.";
                default:
                    return "현재 화면 정보를 안전하게 확인하지 못했어요. 이 단계는 직접 진행해 주세요.";
            }
        }
        if (error instanceof ApiException) {
            ApiException api = (ApiException) error;
            boolean requestEndpoint = "/api/v1/voice-requests/enqueue".equals(api.path)
                    || "/api/v1/voice-requests/next/claim".equals(api.path)
                    || "/api/v1/voice-requests/interrupt-and-clear".equals(api.path)
                    || "/api/v1/agent/commands".equals(api.path);
            if (requestEndpoint && (api.status == 404 || api.status == 405)) {
                return "이 기능은 업데이트가 필요한 상태예요. 지금은 다른 도움을 요청하실 수 있어요.";
            }
            if (api.status == 401) return "로그인이 만료됐어요. 클릭 앱에서 다시 로그인해 주세요.";
            if (api.status == 429) return "요청이 많아 연결이 조금 늦어지고 있어요. 잠시 후 다시 시도해 주시겠어요?";
            if (api.status >= 500) return "서버가 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
        }
        if (error != null && "AUTH_REQUIRED".equals(error.getMessage())) {
            return "로그인이 필요해요. 클릭 앱에서 로그인해 주세요.";
        }
        if (error instanceof SocketTimeoutException) {
            return "서버 응답이 늦어지고 있어요. 잠시 후 요청 상태를 확인해 주세요.";
        }
        if (error instanceof UnknownHostException || error instanceof ConnectException) {
            return "서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.";
        }
        return "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }

    public static String describe(String operation, Throwable error) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        String detail = error == null || error.getMessage() == null ? "메시지 없음" : error.getMessage();
        String hint;
        if (error instanceof SocketTimeoutException) {
            hint = "백엔드 실행 여부, 서버 로그, 네트워크 지연과 API 처리 시간을 확인하세요.";
        } else if (error instanceof UnknownHostException) {
            hint = "API 호스트 주소와 휴대폰의 DNS/Wi-Fi 연결을 확인하세요.";
        } else if (error instanceof ConnectException) {
            hint = "백엔드 프로세스, 포트, 방화벽, 휴대폰과 서버의 네트워크를 확인하세요.";
        } else if (error instanceof ApiException) {
            hint = "표시된 HTTP 상태·경로·응답 본문과 백엔드 로그를 대조하세요.";
        } else {
            hint = "예외 종류와 상세 메시지를 기준으로 앱 로그와 백엔드 로그를 확인하세요.";
        }
        return operation + " 실패\n"
                + "API: " + BuildConfig.CLICK_API_BASE_URL + "\n"
                + "예외: " + type + "\n"
                + "상세: " + detail + "\n"
                + "확인: " + hint;
    }
}
