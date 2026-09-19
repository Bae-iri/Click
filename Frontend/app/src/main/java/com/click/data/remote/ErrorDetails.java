package com.click.data.remote;

import com.click.BuildConfig;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class ErrorDetails {
    private ErrorDetails() {}

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
