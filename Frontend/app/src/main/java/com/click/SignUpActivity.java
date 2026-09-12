package com.click;

import android.content.Intent;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Patterns;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.click.data.local.LocalAccountStore;
import com.click.ui.common.ClickUi;

public final class SignUpActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = ClickUi.page(this);
        page.addView(ClickUi.title(this, "회원가입"));
        page.addView(ClickUi.body(this, "안내를 더 편하게 맞춰드리기 위해\n아래 정보를 입력해주세요."), ClickUi.wrap(ClickUi.dp(this, 14)));

        EditText email = ClickUi.input(this, "예) click@gmail.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText age = ClickUi.input(this, "나이", InputType.TYPE_CLASS_NUMBER);
        EditText password = ClickUi.input(this, "예) 123456", ClickUi.passwordType());
        EditText passwordConfirm = ClickUi.input(this, "비밀번호를 다시 입력해주세요", ClickUi.passwordType());
        TextView passwordGuide = ClickUi.body(this, "비밀번호를 6글자 이상으로 입력해주세요.");
        passwordGuide.setTextSize(13);
        Spinner job = new Spinner(this);
        String[] jobs = {
                "직업을 선택해주세요", "IT/소프트웨어", "제조업", "서비스업", "사무/관리",
                "자영업", "교육/연구", "의료/보건", "공무원", "주부", "학생", "무직", "기타"
        };
        job.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, jobs));
        Spinner gender = new Spinner(this);
        String[] genders = {"성별을 선택해주세요", "남성", "여성"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, genders);
        gender.setAdapter(adapter);

        page.addView(email, ClickUi.fullWidth(this, 62, 24));
        page.addView(age, ClickUi.fullWidth(this, 62, 10));
        page.addView(job, ClickUi.fullWidth(this, 62, 10));
        page.addView(gender, ClickUi.fullWidth(this, 62, 10));
        page.addView(password, ClickUi.fullWidth(this, 62, 10));
        page.addView(passwordGuide, ClickUi.wrap(ClickUi.dp(this, 4)));
        page.addView(passwordConfirm, ClickUi.fullWidth(this, 62, 6));

        android.widget.Button complete = ClickUi.primaryButton(this, "가입하고 튜토리얼 시작");
        complete.setOnClickListener(view -> {
            String emailValue = email.getText().toString().trim();
            String ageValue = age.getText().toString().trim();
            String passwordValue = password.getText().toString();
            String passwordConfirmValue = passwordConfirm.getText().toString();
            email.setError(null);
            password.setError(null);
            passwordConfirm.setError(null);
            if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
                email.setError("올바른 이메일 형식으로 입력해주세요.");
                email.requestFocus();
                return;
            }
            if (passwordValue.length() < 6) {
                password.setError("비밀번호를 6글자 이상으로 입력해주세요.");
                password.requestFocus();
                return;
            }
            if (!passwordValue.equals(passwordConfirmValue)) {
                passwordConfirm.setError("비밀번호가 일치하지 않습니다.");
                passwordConfirm.requestFocus();
                return;
            }
            if (ageValue.isEmpty() || job.getSelectedItemPosition() == 0
                    || gender.getSelectedItemPosition() == 0) {
                Toast.makeText(this, "모든 항목을 입력해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            new LocalAccountStore(this).saveProfile(
                    emailValue, ageValue, job.getSelectedItem().toString(), gender.getSelectedItem().toString());
            showAccessibilityConsent();
        });
        page.addView(complete, ClickUi.fullWidth(this, 68, 26));
        setContentView(ClickUi.scrollPage(this, page));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            android.view.View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect bounds = new Rect();
                focused.getGlobalVisibleRect(bounds);
                if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                    focused.clearFocus();
                    InputMethodManager keyboard =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void showAccessibilityConsent() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("접근성 권한이 필요해요")
                .setMessage("Click AI가 화면에서 설정 버튼을 찾고, 필요한 위치를 안내하거나 스크롤하려면 접근성 권한이 필요합니다.\n\n동의하면 휴대폰의 접근성 설정 화면으로 이동합니다. 목록에서 Click을 찾아 사용을 허용해 주세요.")
                .setNegativeButton("동의하지 않습니다", (dialog, which) -> showAccessibilityDeclined())
                .setPositiveButton("방법 보고 설정하기", (dialog, which) ->
                        startActivity(new Intent(this, AccessibilityPermissionTutorialActivity.class)))
                .show();
    }

    private void showAccessibilityDeclined() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("권한 없이는 안내 기능을 사용할 수 없어요")
                .setMessage("접근성 권한에 동의하지 않으면 Click AI가 현재 화면의 버튼을 확인하거나, 튜토리얼에서 버튼을 가리키고 자동으로 스크롤할 수 없습니다. 따라서 핵심 안내 서비스를 제공하기 어렵습니다.\n\n권한 설정을 다시 진행하시겠어요?")
                .setNegativeButton("나중에 하기", null)
                .setPositiveButton("다시 동의하기", (dialog, which) -> showAccessibilityConsent())
                .show();
    }

}
