package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.click.service.overlay.ClickOverlayService;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int spacing = dp(24);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(spacing, spacing, spacing, spacing);
        content.setBackgroundColor(Color.rgb(250, 250, 252));

        TextView title = new TextView(this);
        title.setText(R.string.overlay_demo_title);
        title.setTextSize(24);
        title.setTextColor(Color.rgb(24, 33, 47));
        title.setGravity(Gravity.CENTER);

        TextView description = new TextView(this);
        description.setText(R.string.overlay_demo_description);
        description.setTextSize(15);
        description.setTextColor(Color.rgb(83, 94, 112));
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(12), 0, dp(28));

        Button start = new Button(this);
        start.setText(R.string.start_overlay);
        start.setOnClickListener(view -> startClickOverlay());

        Button stop = new Button(this);
        stop.setText(R.string.stop_overlay);
        stop.setOnClickListener(view -> stopService(new Intent(this, ClickOverlayService.class)));

        content.addView(title, matchWrap());
        content.addView(description, matchWrap());
        content.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        stopParams.topMargin = dp(10);
        content.addView(stop, stopParams);
        setContentView(content);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST && Settings.canDrawOverlays(this)) {
            startService(new Intent(this, ClickOverlayService.class));
        }
    }

    private void startClickOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent permissionIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(permissionIntent, OVERLAY_PERMISSION_REQUEST);
            return;
        }
        startService(new Intent(this, ClickOverlayService.class));
        moveTaskToBack(true);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
