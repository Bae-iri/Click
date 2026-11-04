package com.click.designpreview;


/** Phone, PIN and optional-name rules matching the deployed authentication API. */
public final class SignupInputRules {
    public static final int MAXIMUM_NAME_LENGTH = 50;
    private SignupInputRules() {}

    public static String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim().replace(" ", "").replace("-", "");
    }

    public static boolean validPhone(String phone) {
        return normalizePhone(phone).matches("01[016789][0-9]{7,8}");
    }

    public static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4}");
    }

    public static boolean validOptionalName(String name) {
        String value = name == null ? "" : name.trim();
        return value.codePointCount(0, value.length()) <= MAXIMUM_NAME_LENGTH;
    }
}
