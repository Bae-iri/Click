package com.click.data.remote;

/** Only a confirmed password-length failure may display password-length advice. */
public final class AuthErrorMessages {
    private AuthErrorMessages() {}

    public static String registration(Throwable error) {
        if (error instanceof RegistrationContract.PasswordLengthException
                || error instanceof RegistrationContract.RegistrationInputException) return error.getMessage();
        if (error instanceof ApiException && ((ApiException) error).status == 422) {
            return "가입 정보에서 확인이 필요한 부분이 있어요. 전화번호와 이름을 한 번 더 확인해 주세요.";
        }
        return "가입을 완료하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.";
    }
}
