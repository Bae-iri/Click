package com.click.data.snapshot;

import java.util.Locale;

/** Fail-closed policy evaluated before any screenshot is requested. */
public final class VisionSafetyPolicy {
    private static final String[] SENSITIVE_PACKAGES = {
            ".bank", "banking", ".wallet", ".pay", "payment", "finance",
            "com.android.systemui", "inputmethod", "honeyboard", ".keyboard"
    };
    private static final String[] SENSITIVE_SCREEN_TERMS = {
            "card number", "security code", "cvv", "cvc", "account number",
            "payment", "checkout", "transfer money", "송금", "결제", "카드 번호",
            "보안 코드", "계좌 번호", "주민등록번호", "인증번호", "일회용 비밀번호"
    };

    private VisionSafetyPolicy() {}

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    public static Decision assess(ScreenSnapshot screen) {
        if (screen == null) return new Decision(false, "missing_screen_context");
        String packageName = normalize(screen.packageName);
        for (String fragment : SENSITIVE_PACKAGES) {
            if (packageName.contains(fragment)) return new Decision(false, "sensitive_package");
        }
        StringBuilder visible = new StringBuilder();
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (!node.visibleToUser) continue;
            if (node.sensitive) return new Decision(false, "password_or_secret_input");
            if (node.text != null) visible.append(' ').append(node.text);
            if (node.contentDescription != null) visible.append(' ').append(node.contentDescription);
        }
        return assessVisibleText(visible.toString());
    }

    /** Second gate for screens whose Accessibility tree was empty. Runs locally after OCR. */
    public static Decision assessVisibleText(String visibleText) {
        String labels = normalize(visibleText);
        for (String term : SENSITIVE_SCREEN_TERMS) {
            if (labels.contains(term)) return new Decision(false, "sensitive_screen_content");
        }
        return new Decision(true, "allowed");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
