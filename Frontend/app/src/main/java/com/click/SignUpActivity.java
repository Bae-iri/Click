package com.click;

/** Compatibility entry point: all account screens now live in the unified Click UI. */
public final class SignUpActivity extends android.app.Activity {
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        startActivity(new android.content.Intent(this, com.click.designpreview.SignupActivity.class));
        finish();
    }
}
