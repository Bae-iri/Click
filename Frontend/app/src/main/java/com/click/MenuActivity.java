package com.click;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.click.core.permission.PermissionStatus;
import com.click.data.local.LocalAccountStore;
import com.click.service.overlay.ClickOverlayService;
import com.click.data.local.AuthTokenStore;
import com.click.ui.common.ClickUi;

public final class MenuActivity extends AppCompatActivity {
    private static final String[] JOBS = {
            "직업을 선택해주세요", "IT/소프트웨어", "제조업", "서비스업", "사무/관리",
            "자영업", "교육/연구", "의료/보건", "공무원", "주부", "학생", "무직", "기타"
    };
    private static final String[] GENDERS = {"성별을 선택해주세요", "남성", "여성"};
    private TextView permissionStatus;
    private TextView profile;
    private LocalAccountStore account;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = ClickUi.page(this);
        account = new LocalAccountStore(this);
        page.addView(ClickUi.title(this, "메뉴"), ClickUi.wrap(ClickUi.dp(this, 8)));

        LinearLayout profileHeader = new LinearLayout(this);
        profileHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView profileTitle = ClickUi.body(this, "내 정보");
        profileTitle.setTextSize(22);
        profileTitle.setTextColor(ClickUi.TEXT);
        profileHeader.addView(profileTitle, new LinearLayout.LayoutParams(0, ClickUi.dp(this, 56), 1f));
        android.widget.Button edit = ClickUi.secondaryButton(this, "수정하기");
        edit.setOnClickListener(view -> showEditProfile());
        profileHeader.addView(edit, new LinearLayout.LayoutParams(ClickUi.dp(this, 120), ClickUi.dp(this, 52)));
        page.addView(profileHeader, ClickUi.fullWidth(this, 56, 26));

        profile = ClickUi.body(this, "");
        profile.setPadding(ClickUi.dp(this, 18), ClickUi.dp(this, 16),
                ClickUi.dp(this, 18), ClickUi.dp(this, 16));
        profile.setBackgroundColor(Color.WHITE);
        page.addView(profile, ClickUi.wrap(ClickUi.dp(this, 6)));
        refreshProfile();

        permissionStatus = ClickUi.body(this, "");
        permissionStatus.setTextSize(18);
        page.addView(permissionStatus, ClickUi.wrap(ClickUi.dp(this, 24)));

        android.widget.Button tutorial = ClickUi.primaryButton(this, "글자 크기 튜토리얼");
        tutorial.setOnClickListener(view -> startActivity(new Intent(this, TutorialActivity.class)));
        page.addView(tutorial, ClickUi.fullWidth(this, 64, 24));

        android.widget.Button logout = ClickUi.secondaryButton(this, "로그아웃");
        logout.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("로그아웃할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("로그아웃", (dialog, which) -> returnToLogin())
                .show());
        page.addView(logout, ClickUi.fullWidth(this, 60, 18));

        android.widget.Button delete = ClickUi.secondaryButton(this, "회원탈퇴");
        delete.setTextColor(Color.rgb(196, 54, 65));
        delete.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("회원탈퇴를 진행할까요?")
                .setMessage("저장된 회원 정보가 삭제되며 되돌릴 수 없습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("탈퇴", (dialog, which) -> {
                    stopService(new Intent(this, ClickOverlayService.class));
                    account.deleteAccount();
                    returnToLogin();
                }).show());
        page.addView(delete, ClickUi.fullWidth(this, 60, 10));

        android.widget.Button home = ClickUi.secondaryButton(this, "←  홈으로");
        home.setOnClickListener(view -> closeMenu());
        page.addView(home, ClickUi.fullWidth(this, 60, 18));
        setContentView(ClickUi.scrollPage(this, page));
    }

    private void showEditProfile() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ClickUi.dp(this, 24), ClickUi.dp(this, 8), ClickUi.dp(this, 24), 0);
        EditText email = ClickUi.input(this, "이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(account.email());
        EditText age = ClickUi.input(this, "나이", InputType.TYPE_CLASS_NUMBER);
        age.setText(account.age());
        Spinner job = spinner(JOBS, account.job());
        Spinner gender = spinner(GENDERS, account.gender());
        form.addView(email, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 58)));
        form.addView(age, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 58)));
        form.addView(job, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 58)));
        form.addView(gender, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 58)));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("내 정보 수정")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String emailValue = email.getText().toString().trim();
                    String ageValue = age.getText().toString().trim();
                    if (emailValue.isEmpty() || ageValue.isEmpty()
                            || job.getSelectedItemPosition() == 0 || gender.getSelectedItemPosition() == 0) {
                        android.widget.Toast.makeText(this, "모든 정보를 입력해주세요.", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    account.saveProfile(emailValue, ageValue,
                            job.getSelectedItem().toString(), gender.getSelectedItem().toString());
                    refreshProfile();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private Spinner spinner(String[] values, String selected) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) spinner.setSelection(i);
        }
        return spinner;
    }

    private void refreshProfile() {
        profile.setText("이메일  " + account.email() + "\n나이  " + account.age()
                + "\n직업  " + account.job() + "\n성별  " + account.gender());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean accessibility = PermissionStatus.isAccessibilityEnabled(this);
        boolean overlay = Settings.canDrawOverlays(this);
        boolean microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("권한 상태\n접근성 " + mark(accessibility)
                + "   오버레이 " + mark(overlay) + "   마이크 " + mark(microphone));
        permissionStatus.setTextColor(accessibility && overlay && microphone
                ? Color.rgb(55, 142, 72) : Color.rgb(170, 83, 45));
    }

    private String mark(boolean enabled) { return enabled ? "허용" : "필요"; }

    private void closeMenu() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    public void onBackPressed() {
        closeMenu();
    }

    private void returnToLogin() {
        new AuthTokenStore(this).clear();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
