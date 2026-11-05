package com.click.data.remote;

import com.click.BuildConfig;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class ErrorDetails {
    private ErrorDetails() {}

    /** Shared by typed and spoken requests; diagnostic details belong in the app log. */
    public static String requestMessage(Throwable error) {
        if (error instanceof com.click.data.snapshot.SettingsScreenPrivacy.Blocked)
            return "개인정보가 있거나 지원하지 않는 화면이라 화면 전송을 멈췄어요. 이 단계는 직접 진행해 주세요.";
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
