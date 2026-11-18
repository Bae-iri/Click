package com.click;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Legacy links open the same explanation cards as onboarding and the help menu. */
public final class OverlayTutorialActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, TutorialActivity.class));
        finish();
    }
}
