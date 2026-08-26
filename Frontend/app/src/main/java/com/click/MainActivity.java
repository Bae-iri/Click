package com.click;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView content = new TextView(this);
        content.setText(R.string.app_ready);
        content.setTextSize(18);
        content.setGravity(android.view.Gravity.CENTER);
        setContentView(content);
    }
}
