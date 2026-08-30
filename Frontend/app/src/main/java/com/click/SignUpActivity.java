package com.click;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.click.data.local.LocalAccountStore;
import com.click.ui.common.ClickUi;

public final class SignUpActivity extends AppCompatActivity {
    private final ActivityResultLauncher<Intent> accessibilitySettings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> openTutorial());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = ClickUi.page(this);
        page.addView(ClickUi.title(this, "회원가입"));
        page.addView(ClickUi.body(this, "안내를 더 편하게 맞춰드리기 위해\n아래 정보를 입력해주세요."), ClickUi.wrap(ClickUi.dp(this, 14)));

        EditText email = ClickUi.input(this, "이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText age = ClickUi.input(this, "나이", InputType.TYPE_CLASS_NUMBER);
        EditText password = ClickUi.input(this, "비밀번호", ClickUi.passwordType());
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

        android.widget.Button complete = ClickUi.primaryButton(this, "가입하고 튜토리얼 시작");
        complete.setOnClickListener(view -> {
            String emailValue = email.getText().toString().trim();
            String ageValue = age.getText().toString().trim();
            if (emailValue.isEmpty() || ageValue.isEmpty()
                    || password.getText().toString().length() < 6
                    || job.getSelectedItemPosition() == 0 || gender.getSelectedItemPosition() == 0) {
                Toast.makeText(this, "모든 항목을 입력하고 비밀번호를 6자 이상 설정해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            new LocalAccountStore(this).saveProfile(
                    emailValue, ageValue, job.getSelectedItem().toString(), gender.getSelectedItem().toString());
            showAccessibilityConsent();
        });
        page.addView(complete, ClickUi.fullWidth(this, 68, 26));
        setContentView(ClickUi.scrollPage(this, page));
    }

    private void showAccessibilityConsent() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("접근성 권한이 필요해요")
                .setMessage("Click AI가 화면에서 설정 버튼을 찾고, 필요한 위치를 안내하거나 스크롤하려면 접근성 권한이 필요합니다.\n\n동의하면 휴대폰의 접근성 설정 화면으로 이동합니다. 목록에서 Click을 찾아 사용을 허용해 주세요.")
                .setNegativeButton("동의하지 않습니다", (dialog, which) -> showAccessibilityDeclined())
                .setPositiveButton("동의하고 설정하기", (dialog, which) ->
                        accessibilitySettings.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
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

    private void openTutorial() {
        Intent tutorial = new Intent(this, TutorialActivity.class);
        tutorial.putExtra(TutorialActivity.EXTRA_FROM_SIGN_UP, true);
        startActivity(tutorial);
        finish();
    }
}
