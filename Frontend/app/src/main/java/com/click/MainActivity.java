package com.click;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.click.data.local.LocalAccountStore;
import com.click.ui.common.ClickUi;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalAccountStore account = new LocalAccountStore(this);
        LinearLayout page = ClickUi.page(this);

        page.addView(ClickUi.title(this, "Click 로그인"));
        page.addView(ClickUi.body(this, "큰 글씨와 음성 안내로\n휴대폰 설정을 쉽게 도와드릴게요."),
                ClickUi.wrap(ClickUi.dp(this, 18)));

        EditText email = ClickUi.input(this, "이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(account.email());
        EditText password = ClickUi.input(this, "비밀번호", ClickUi.passwordType());
        page.addView(email, ClickUi.fullWidth(this, 62, 34));
        page.addView(password, ClickUi.fullWidth(this, 62, 12));

        android.widget.Button login = ClickUi.primaryButton(this, "로그인");
        login.setOnClickListener(view -> {
            if (email.getText().toString().trim().isEmpty() || password.getText().toString().isEmpty()) {
                Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, HomeActivity.class));
        });
        page.addView(login, ClickUi.fullWidth(this, 64, 28));

        android.widget.Button signup = ClickUi.secondaryButton(this, "처음이신가요? 회원가입");
        signup.setOnClickListener(view -> startActivity(new Intent(this, SignUpActivity.class)));
        page.addView(signup, ClickUi.fullWidth(this, 60, 12));
        setContentView(ClickUi.scrollPage(this, page));
    }
}
