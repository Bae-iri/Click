package com.click;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.click.data.local.LocalAccountStore;
import com.click.data.local.AuthTokenStore;
import com.click.data.remote.AuthGateway;
import com.click.data.remote.ErrorDetails;
import com.click.ui.common.ClickUi;

public class MainActivity extends AppCompatActivity {
    private AuthGateway authGateway;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalAccountStore account = new LocalAccountStore(this);
        AuthTokenStore tokens = new AuthTokenStore(this);
        if (tokens.hasRefreshToken()) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        authGateway = new AuthGateway(this);
        LinearLayout page = ClickUi.page(this);

        page.addView(ClickUi.title(this, "Click 로그인"));
        page.addView(ClickUi.body(this, "큰 글씨와 음성 안내로\n휴대폰 설정을 쉽게 도와드릴게요."),
                ClickUi.wrap(ClickUi.dp(this, 18)));

        EditText email = ClickUi.input(this, "예) click@gmail.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(account.email());
        EditText password = ClickUi.input(this, "예) click12345678", ClickUi.passwordType());
        page.addView(email, ClickUi.fullWidth(this, 62, 34));
        page.addView(password, ClickUi.fullWidth(this, 62, 12));

        android.widget.Button login = ClickUi.primaryButton(this, "로그인");
        login.setOnClickListener(view -> {
            String emailValue = email.getText().toString().trim();
            String passwordValue = password.getText().toString();
            if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
                email.setError("올바른 이메일 형식으로 입력해주세요.");
                email.requestFocus();
                return;
            }
            if (passwordValue.length() < 12) {
                password.setError("비밀번호를 12글자 이상으로 입력해주세요.");
                password.requestFocus();
                return;
            }
            login.setEnabled(false);
            authGateway.login(emailValue, passwordValue, new AuthGateway.Callback() {
                @Override public void onSuccess() {
                    account.saveProfile(emailValue, account.age(), account.job(), account.gender());
                    startActivity(new Intent(MainActivity.this, HomeActivity.class));
                    finish();
                }

                @Override public void onFailure(Throwable error) {
                    login.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            ErrorDetails.describe("로그인", error), Toast.LENGTH_LONG).show();
                }
            });
        });
        page.addView(login, ClickUi.fullWidth(this, 64, 28));

        android.widget.Button signup = ClickUi.secondaryButton(this, "처음이신가요? 회원가입");
        signup.setOnClickListener(view -> startActivity(new Intent(this, SignUpActivity.class)));
        page.addView(signup, ClickUi.fullWidth(this, 60, 12));
        setContentView(ClickUi.scrollPage(this, page));
    }

    @Override protected void onDestroy() {
        if (authGateway != null) authGateway.close();
        super.onDestroy();
    }
}
