package com.click.data.remote;

/** Only a confirmed password-length failure may display password-length advice. */
public final class AuthErrorMessages {
    private AuthErrorMessages() {}

    public static String registration(Throwable error) {
        if (error instanceof RegistrationContract.PasswordLengthException) return error.getMessage();
        if (error instanceof ApiException && ((ApiException) error).status == 422) {
            return "입력한 내용이 가입 기준과 맞지 않아요. 이메일과 프로필 정보를 다시 확인해 주세요.";
        }
        return "가입을 완료하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.";
    }
}
