package com.click.service.accessibility;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Empty, native private-screen guard probe. Exists only in the instrumentation APK. */
public final class MetaControllerPrivateGuardFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(48,120,48,48);page.setFocusableInTouchMode(true);
        TextView heading=new TextView(this);heading.setText("Native protected guard");heading.setAccessibilityHeading(true);page.addView(heading);
        EditText secret=new EditText(this);secret.setSaveEnabled(false);
        secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);page.addView(secret);
        TextView count=new TextView(this);count.setText("Guard action count 0");count.setContentDescription("Guard action count 0");
        count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);page.addView(count);
        Button action=new Button(this);action.setAllCaps(false);action.setText("Guard probe action");page.addView(action);
        action.setOnClickListener(view->{count.setText("Guard action count 1");count.setContentDescription("Guard action count 1");});
        setContentView(page);page.requestFocus();
    }
}
