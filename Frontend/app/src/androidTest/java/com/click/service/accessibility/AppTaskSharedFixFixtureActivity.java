package com.click.service.accessibility;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public, isolated test UI. Never included in a release APK. */
public final class AppTaskSharedFixFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(48,120,48,48);
        TextView title=new TextView(this);title.setText("실험 시작");title.setAccessibilityHeading(true);
        page.addView(title);
        Button other=new Button(this);other.setText("도움말");page.addView(other);
        Button display=new Button(this);display.setText("디스플레이");page.addView(display);
        TextView proof=new TextView(this);proof.setText("공개 안내");page.addView(proof);
        display.setOnClickListener(v->{
            page.removeAllViews();
            TextView destination=new TextView(this);destination.setText("디스플레이");
            destination.setAccessibilityHeading(true);page.addView(destination);
            TextView landmark=new TextView(this);landmark.setText("밝기 안내");page.addView(landmark);
        });
        setContentView(page);
    }
}
