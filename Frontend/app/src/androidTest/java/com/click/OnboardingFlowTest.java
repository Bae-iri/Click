package com.click;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.click.designpreview.OnboardingActivity;
import com.click.designpreview.LoginActivity;
import com.click.designpreview.PermissionGuideActivity;
import com.click.designpreview.AccessibilityHelpActivity;
import com.click.designpreview.SignupActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class OnboardingFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void threePagesEndWithKakaoAndEmailChoices() throws Exception {
        try (ActivityScenario<OnboardingActivity> s = ActivityScenario.launch(OnboardingActivity.class)) {
            tap("건너뛰기");
            shown("회원가입하기"); shown("이미 가입하셨나요? 로그인하기");
            screenshot("onboarding-third");
            tap("이미 가입하셨나요? 로그인하기");
            shown("카카오 로그인"); shown("이메일로 가입하기"); shown("이메일로 로그인하기");
            clickView("이메일로 로그인하기"); shown("다시 만나 반가워요");
        }
    }
    @Test public void emailReviewPrecedesProfileAndOccupationIsSelectable() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            awaitSignupRules(s);
            s.onActivity(a -> {
                ((EditText)a.findViewById(com.click.designpreview.R.id.signup_email)).setText("review@example.com");
                EditText password = a.findViewById(com.click.designpreview.R.id.signup_password);
                password.setText("sample-pass13");
                ((EditText)a.findViewById(com.click.designpreview.R.id.signup_password_confirm)).setText("sample-pass13");
                a.findViewById(com.click.designpreview.R.id.signup_password_toggle).performClick();
                a.findViewById(com.click.designpreview.R.id.signup_password_toggle).performClick();
                assertEquals("sample-pass13", password.getText().toString());
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("이메일을\n확인해 주세요"); screenshot("email-review");
            clickView("이메일 수정");
            s.onActivity(a -> a.findViewById(com.click.designpreview.R.id.signup_action).performClick());
            clickView("이 이메일로 계속"); shown("안내를 조금 더\n원활히 도와드릴게요");
            shown("이름 (선택)");
            s.onActivity(a -> ((EditText) a.findViewById(
                    com.click.designpreview.R.id.signup_name)).setText("김클릭"));
            screenshot("signup-profile-name");
            s.onActivity(a -> a.findViewById(com.click.designpreview.R.id.signup_occupation).performClick());
            clickView("서비스업"); screenshot("occupation");
            s.onActivity(a -> assertTrue(((TextView)a.findViewById(com.click.designpreview.R.id.signup_occupation))
                    .getText().toString().contains("서비스업")));
        }
    }
    @Test public void accessibilityRequirementLinksToInAppGuide() throws Exception {
        instrumentation.getUiAutomation().executeShellCommand(
                "settings delete secure enabled_accessibility_services").close();
        instrumentation.getUiAutomation().executeShellCommand(
                "settings put secure accessibility_enabled 0").close();
        // Secure-setting changes restart the accessibility manager asynchronously.
        SystemClock.sleep(1500);
        try (ActivityScenario<AccessibilityHelpActivity> s = ActivityScenario.launch(
                AccessibilityHelpActivity.class)) {
            shown("아직은 보고 계신 화면을\n확인할 수 없어요");
            shown("지금은 접근성 허용이 되지 않아\n클릭이 도와드릴 수 없어요.\n\n접근성 설정에서 클릭을 허용해 주시면\n바로 도와드릴게요.");
            shown("접근성 설정하러 가기"); shown("지금은 앱 닫기"); screenshot("accessibility-later");
            s.onActivity(a -> {
                assertFalse("테스트 시작 시 Click 접근성 서비스는 꺼져 있어야 합니다.",
                        com.click.designpreview.ClickHost.from(a).accessibilityEnabled());
                assertTrue(a.findViewById(
                        com.click.designpreview.R.id.accessibility_help_action).performClick());
            });
            SystemClock.sleep(500);
            final Activity[] opened = {null};
            final StringBuilder resumed = new StringBuilder();
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (resumed.length() > 0) resumed.append(", ");
                    resumed.append(activity.getClass().getName());
                    if (activity instanceof PermissionGuideActivity) opened[0] = activity;
                }
            });
            Activity guide = opened[0];
            assertNotNull("앱 안의 접근성 설정 안내 화면이 열려야 합니다. 현재 화면: " + resumed, guide);
            instrumentation.runOnMainSync(guide::finish);
        }
    }
    @Test public void occupationExpandsInlineAndSelectsImmediately() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            s.onActivity(a -> {
                try {
                    Method showPage = SignupActivity.class.getDeclaredMethod(
                            "showPage", int.class, boolean.class);
                    showPage.setAccessible(true);
                    showPage.invoke(a, 1, false);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                a.findViewById(com.click.designpreview.R.id.signup_occupation).performClick();
                View options = a.findViewById(com.click.designpreview.R.id.signup_occupation_options);
                assertEquals(View.VISIBLE, options.getVisibility());
                assertSame(a.findViewById(com.click.designpreview.R.id.signup_profile_content),
                        options.getParent());
            });
            shown("선택하지 않음"); screenshot("occupation-expanded");
            clickView("서비스업");
            s.onActivity(a -> {
                TextView field = a.findViewById(com.click.designpreview.R.id.signup_occupation);
                android.widget.RadioGroup options = a.findViewById(
                        com.click.designpreview.R.id.signup_occupation_options);
                assertEquals("서비스업", field.getText().toString());
                assertEquals(View.GONE, options.getVisibility());
                field.performClick();
                assertEquals("서비스업", ((TextView) options.findViewById(
                        options.getCheckedRadioButtonId())).getText().toString());
            });
            clickView("서비스업");
            s.onActivity(a -> assertEquals(View.GONE, a.findViewById(
                    com.click.designpreview.R.id.signup_occupation_options).getVisibility()));
            screenshot("occupation-selected");
        }
    }
    @Test public void loginMatchesSignupAndValidatesInline() throws Exception {
        try (ActivityScenario<LoginActivity> s = ActivityScenario.launch(LoginActivity.class)) {
            shown("다시 만나 반가워요");
            shown("가입할 때 사용한\n이메일과 비밀번호를 입력해 주세요.");
            shown("로그인하기"); shown("다른 방법으로 시작하기");
            screenshot("login");
            s.onActivity(a -> {
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_email))
                        .setText("not-an-email");
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_password))
                        .setText("short");
                a.findViewById(com.click.designpreview.R.id.login_action).performClick();
            });
            shown("이메일 주소를 한 번 더 확인해 주세요.");
            shown("가입할 때 설정한 비밀번호를 입력해 주세요.");
            s.onActivity(a -> {
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_email))
                        .setText("review@example.com");
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_password)).setText("");
                a.findViewById(com.click.designpreview.R.id.login_action).performClick();
            });
            shown("비밀번호를 입력해 주세요.");
        }
    }

    @Test public void signupPasswordHintAndValidationUseLoadedServerMinimum() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            awaitSignupRules(s);
            final int[] minimum = {0};
            s.onActivity(a -> {
                EditText password = a.findViewById(com.click.designpreview.R.id.signup_password);
                minimum[0] = Integer.parseInt(password.getHint().toString().split("글자")[0]);
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_email))
                        .setText("review@example.com");
                String shortPassword = new String(new char[minimum[0] - 1]).replace('\0', 'a');
                password.setText(shortPassword);
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password_confirm))
                        .setText(shortPassword);
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("비밀번호를 " + minimum[0] + "글자 이상 입력해 주세요.");
            screenshot("signup-password-minimum");
            s.onActivity(a -> {
                String validPassword = new String(new char[minimum[0]]).replace('\0', 'a');
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password))
                        .setText(validPassword);
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password_confirm))
                        .setText(validPassword);
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("이메일을\n확인해 주세요");
            clickView("이메일 수정");
        }
    }

    private void awaitSignupRules(ActivityScenario<SignupActivity> scenario) {
        final boolean[] ready = {false};
        long deadline = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(a -> ready[0] = "다음".contentEquals(((TextView) a.findViewById(
                    com.click.designpreview.R.id.signup_action_label)).getText())
                    && a.findViewById(com.click.designpreview.R.id.signup_action).isEnabled());
            if (ready[0]) return;
            SystemClock.sleep(100);
        }
        fail("회원가입 기준을 불러오지 못했습니다.");
    }
    @Test public void homeMatchesStartAndFavoritesReference() throws Exception {
        try (ActivityScenario<HomeActivity> s = ActivityScenario.launch(HomeActivity.class)) {
            shown("안녕하세요!");
            shown("오늘도\n클릭과 함께\n해볼까요?");
            s.onActivity(a -> assertEquals("궁금한 기능을 적거나 말해보세요!",
                    ((EditText) a.findViewById(R.id.home_request_input)).getHint().toString()));
            shown("즐겨찾기");
            shown("카카오톡");
            shown("글씨 크기");
            shown("화면 밝기");
            shown("동영상·음악 소리");
            shown("전화");
            shown("클릭 튜토리얼");
            shown("홈");

            screenshot("home-current");
            s.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            SystemClock.sleep(500);
            s.onActivity(a -> {
                assertTrue(a.findViewById(R.id.home_nav_favorites).isSelected());
                assertEquals(View.GONE, a.findViewById(R.id.home_scroll).getVisibility());
                assertTrue(a.findViewById(R.id.home_favorites_scroll).isShown());
                assertNotNull(a.findViewById(R.id.home_favorites_add));
                a.findViewById(R.id.home_nav_home).performClick();
            });
            SystemClock.sleep(500);
            s.onActivity(a -> {
                assertTrue(a.findViewById(R.id.home_nav_home).isSelected());
                assertEquals(0, a.findViewById(R.id.home_scroll).getScrollY());
            });
        }
    }
    @Test public void favoritesCanBeRemovedAddedAndKeptEmptyAfterRecreation() throws Exception {
        android.content.SharedPreferences prefs = instrumentation.getTargetContext()
                .getSharedPreferences("home_favorites", android.content.Context.MODE_PRIVATE);
        boolean existed = prefs.contains("selected_ids");
        java.util.Set<String> original = new java.util.HashSet<>(prefs.getStringSet("selected_ids", java.util.Collections.emptySet()));
        prefs.edit().clear().commit();
        try (ActivityScenario<HomeActivity> s = ActivityScenario.launch(HomeActivity.class)) {
            s.onActivity(a -> {
                View home = a.findViewById(R.id.home_scroll);
                home.findViewWithTag("favorite_font_size").performClick();
                assertNull(home.findViewWithTag("favorite_font_size"));
                a.findViewById(R.id.home_nav_favorites).performClick();
                a.findViewById(R.id.home_favorites_add).performClick();
                View star = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("favorite_font_size");
                assertFalse(star.isSelected());
                star.performClick();
                assertTrue(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("favorite_font_size").isSelected());
            });
            s.recreate();
            s.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.home_favorites_done));
                assertTrue(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("favorite_font_size").isSelected());
                a.findViewById(R.id.home_favorites_done).performClick();
                a.findViewById(R.id.home_nav_home).performClick();
                assertNotNull(a.findViewById(R.id.home_scroll).findViewWithTag("favorite_font_size"));
                for (com.click.ui.home.HomeHelpItem item : com.click.ui.home.HomeHelpItem.values()) {
                    View star = a.findViewById(R.id.home_scroll).findViewWithTag("favorite_" + item.id);
                    if (star != null) star.performClick();
                }
            });
            s.recreate();
            shown("자주 쓰는 기능을 모아볼까요?");
            s.onActivity(a -> {
                assertTrue(new com.click.data.local.HomeFavoritesStore(a).selectedIds().isEmpty());
                a.findViewById(R.id.home_nav_favorites).performClick();
                a.findViewById(R.id.home_favorites_add).performClick();
                a.findViewById(R.id.home_favorites_scroll).findViewWithTag("favorite_accessibility").performClick();
                a.findViewById(R.id.home_favorites_done).performClick();
                assertNotNull(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("favorite_accessibility"));
                a.findViewById(R.id.home_nav_home).performClick();
                assertNotNull(a.findViewById(R.id.home_scroll).findViewWithTag("favorite_accessibility"));
            });
        } finally {
            if (existed) prefs.edit().putStringSet("selected_ids", original).commit();
            else prefs.edit().remove("selected_ids").commit();
        }
    }
    @Test public void menuMatchesProfileCardReference() throws Exception {
        try (ActivityScenario<MenuActivity> s = ActivityScenario.launch(MenuActivity.class)) {
            shown("설정");
            shown("앱 권한 확인");
            shown("나에게 맞는 안내");
            shown("클릭 사용법");
            shown("서비스 이용약관");
            shown("개인정보 처리방침");
            shown("로그아웃");
            shown("회원 탈퇴");
            screenshot("menu-current");
        }
    }
    @Test public void fourthGuidePageOpensAccessibilitySettingsDirectly() throws Exception {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                new android.content.IntentFilter(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try (ActivityScenario<PermissionGuideActivity> s = ActivityScenario.launch(PermissionGuideActivity.class)) {
            s.onActivity(a -> {
                for (int i = 0; i < 3; i++) {
                    a.findViewById(com.click.designpreview.R.id.guide_action).performClick();
                }
            });
            shown("이 버튼을 누르면\n‘설정 > 접근성’으로 바로 이동해요.");
            screenshot("permission-settings-destination");
            s.onActivity(a -> a.findViewById(
                    com.click.designpreview.R.id.guide_settings_shortcut).performClick());
            assertEquals("접근성 설정 Intent를 열어야 합니다.", 1, monitor.getHits());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }
    @Test public void postAccessibilityScreensShareClickBrand() throws Exception {
        instrumentation.getUiAutomation().executeShellCommand(
                "settings put secure enabled_accessibility_services com.click/com.click.service.accessibility.ClickAccessibilityService").close();
        instrumentation.getUiAutomation().executeShellCommand(
                "settings put secure accessibility_enabled 1").close();
        SystemClock.sleep(500);
        try (ActivityScenario<AccessibilityHelpActivity> s = ActivityScenario.launch(
                AccessibilityHelpActivity.class)) {
            shown("접근성 설정이\n완료됐어요");
            shown("클릭 시작하기");
            screenshot("accessibility-complete");
        }
        try (ActivityScenario<HomeActivity> s = ActivityScenario.launch(HomeActivity.class)) {
            shown("오늘도\n클릭과 함께\n해볼까요?");
            shown("즐겨찾기");
            shown("클릭 튜토리얼");
            screenshot("home");
        }
        try (ActivityScenario<MenuActivity> s = ActivityScenario.launch(MenuActivity.class)) {
            shown("설정");
            shown("앱 권한 확인");
            shown("나에게 맞는 안내");
            shown("클릭 사용법");
            shown("로그아웃");
            screenshot("menu");
        }
        try (ActivityScenario<OverlayTutorialActivity> s = ActivityScenario.launch(
                OverlayTutorialActivity.class)) {
            shown("화면 위에\nClick이 떠 있어요");
            screenshot("overlay-tutorial");
        }
    }
    private TextView shown(String text) {
        instrumentation.waitForIdleSync();
        final TextView[] result = {null};
        for (int attempt = 0; attempt < 20; attempt++) {
            instrumentation.runOnMainSync(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    TextView candidate = find(root, text); if (candidate != null && candidate.isShown()) result[0] = candidate;
                }
            });
            if (result[0] != null) return result[0];
            SystemClock.sleep(100);
        }
        fail("Visible text missing: " + text); return null;
    }
    private void tap(String text) {
        TextView view = shown(text); Rect bounds = new Rect();
        instrumentation.runOnMainSync(() -> {
            assertTrue(view.getGlobalVisibleRect(bounds));
            int[] location = new int[2]; view.getLocationOnScreen(location);
            bounds.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        });
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true)); down.recycle(); up.recycle();
        instrumentation.waitForIdleSync(); SystemClock.sleep(250);
    }
    private void clickView(String text) {
        TextView view = shown(text);
        instrumentation.runOnMainSync(view::performClick);
        instrumentation.waitForIdleSync(); SystemClock.sleep(250);
    }
    private TextView find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText()) && root.isShown()) return (TextView)root;
        if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++) {
            TextView v = find(((ViewGroup)root).getChildAt(i), text); if (v != null) return v;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); SystemClock.sleep(300);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }
}
