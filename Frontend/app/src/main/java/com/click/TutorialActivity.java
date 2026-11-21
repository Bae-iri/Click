package com.click;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Restores older task stacks into the tour hosted by the real home. */
public final class TutorialActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(HomeActivity.tutorialIntent(this));
        finish();
    }
}
