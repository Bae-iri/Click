package com.click.designpreview;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

/** Click-styled account recovery choices, shared with the signup screen's typography. */
final class AccountExistsDialog {
    private AccountExistsDialog() {}

    static void show(Activity activity, String email, Runnable editEmail, Runnable login) {
        View focused = activity.getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_account_exists);
        dialog.setCanceledOnTouchOutside(true);
        ((TextView) dialog.findViewById(R.id.account_exists_email)).setText(email);
        View title = dialog.findViewById(R.id.account_exists_title);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        dialog.findViewById(R.id.account_exists_login).setBackground(
                OnboardingDrawables.startButton(activity.getResources()));
        dialog.findViewById(R.id.account_exists_login).setOnClickListener(view -> {
            dialog.dismiss();
            login.run();
        });
        dialog.findViewById(R.id.account_exists_edit).setOnClickListener(view -> {
            dialog.dismiss();
            editEmail.run();
        });
        dialog.findViewById(R.id.account_exists_close).setOnClickListener(view -> dialog.dismiss());

        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.38f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels
                - AccountUi.dp(activity, 40), AccountUi.dp(activity, 400));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }
}
