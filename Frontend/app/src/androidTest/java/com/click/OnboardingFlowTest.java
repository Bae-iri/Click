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
import com.click.designpreview.AccountStartActivity;
import com.click.designpreview.TextSizeSetupActivity;
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

    @Test public void bothAutomationEntryPointsRequireFullReadingBeforeEnable() throws Exception {
        com.click.data.local.TouchAutomationSettings settings = new com.click.data.local.TouchAutomationSettings(
                instrumentation.getTargetContext());
        boolean original = settings.isEnabled();
        settings.setEnabled(false);
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            for (int route = 0; route < 2; route++) {
                if (route == 0) clickView("자동 모드"); else tap("자동으로 도와주기");
                shown("안내를 끝까지 읽고 자동 모드를 켜주세요.");
                assertFalse(settings.isEnabled());
                scenario.onActivity(activity -> assertFalse(((androidx.appcompat.widget.SwitchCompat)
                        find(activity.getWindow().getDecorView(), "자동 모드")).isChecked()));
                final View[] dialog = {null};
                instrumentation.runOnMainSync(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (root.findViewWithTag("click_dialog_body_scroll") != null) dialog[0] = root;
                    }
                    assertNotNull(dialog[0]);
                    View confirm = dialog[0].findViewById(android.R.id.button1);
                    assertFalse(confirm.isEnabled());
                    confirm.performClick();
                    assertFalse(settings.isEnabled());
                    android.widget.ScrollView body = dialog[0].findViewWithTag("click_dialog_body_scroll");
                    assertTrue(body.canScrollVertically(1));
                    int end = body.getChildAt(0).getHeight() - body.getHeight();
                    body.scrollTo(0, end / 2);
                    assertFalse(confirm.isEnabled());
                    body.scrollTo(0, end);
                    assertTrue(confirm.isEnabled());
                    assertFalse(settings.isEnabled());
                });
                screenshot("automation-read-complete-" + route);
                clickView("자동 모드 켜기");
                assertTrue(settings.isEnabled());
                scenario.recreate();
                assertTrue(settings.isEnabled());
                assertNoWindowText("안내를 끝까지 읽고 자동 모드를 켜주세요.");
                clickView("자동 모드");
                assertFalse(settings.isEnabled());
                if (route == 0) clickView("자동 모드"); else tap("자동으로 도와주기");
                shown("안내를 끝까지 읽고 자동 모드를 켜주세요.");
                instrumentation.runOnMainSync(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (root.findViewWithTag("click_dialog_body_scroll") != null)
                            assertFalse(root.findViewById(android.R.id.button1).isEnabled());
                    }
                });
                clickView("닫기");
                assertFalse(settings.isEnabled());
            }
        } finally { settings.setEnabled(original); }
    }

    private void assertNoWindowText(String value) {
        instrumentation.runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) assertNull(find(root, value));
        });
    }

    @Test public void onboardingEndsWithSignupThenOffersSeparateLogin() throws Exception {
        try (ActivityScenario<OnboardingActivity> s = ActivityScenario.launch(OnboardingActivity.class)) {
            screenshot("onboarding-clue-welcome");
            tap("시작하기");
            shown("글자 크기\n어떻게 키우더라?");
            s.onActivity(a -> {
                assertFalse(a.findViewById(com.click.designpreview.R.id.onboarding_step_three).isShown());
                assertEquals("온보딩, 2단계 중 2단계", a.findViewById(com.click.designpreview.R.id.onboarding_progress).getContentDescription());
            });
            tap("이전"); shown("핸드폰 사용을 위해\n누군가에게 도움을\n구한 적이 있나요?"); tap("시작하기");
            shown("가입하기"); assertNoWindowText("전화번호로 로그인하기");
            screenshot("onboarding-third");
            tap("가입하기");
            shown("회원가입");
            shown("카카오로 가입하기"); shown("번호로 가입하기"); shown("로그인하기");
            assertNoWindowText("또는");
            screenshot("account-login-options");
            tap("로그인하기"); shown("로그인"); shown("카카오로 로그인");
        }
    }

    @Test public void signupChoicesRemainAboveLoginAfterRecreation() throws Exception {
        try (ActivityScenario<AccountStartActivity> s = ActivityScenario.launch(AccountStartActivity.class)) {
            shown("카카오로 가입하기"); shown("번호로 가입하기");
            shown("환영합니다!\n저는 클루라고 해요.");
            screenshot("signup-clue-welcome");
            s.recreate();
            shown("회원가입");
            s.onActivity(a -> {
                View login = a.findViewById(com.click.designpreview.R.id.account_start_login);
                View signup = a.findViewById(com.click.designpreview.R.id.account_start_email);
                assertTrue("Login follows the signup buttons", login.getTop() > signup.getBottom());
            });
            tap("번호로 가입하기");
            shown("전화번호로 가입해요");
            screenshot("email-signup-explicit");
        }
    }

    @Test public void textSizePrecedesOnboardingAndUsesTheSameSettingEverywhere() throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        int original = com.click.ui.common.ClickTextScale.level(context);
        com.click.ui.common.ClickTextScale.saveLevel(context, 1);
        try (ActivityScenario<TextSizeSetupActivity> scenario = ActivityScenario.launch(TextSizeSetupActivity.class)) {
            shown("이 정도 글씨 괜찮으세요?");
            screenshot("signup-text-size-default");
            final float[] titleSize = {0};
            scenario.onActivity(a -> {
                TextView title = a.findViewById(com.click.designpreview.R.id.text_size_setup_title);
                titleSize[0] = title.getTextSize();
                assertNull(a.findViewById(com.click.designpreview.R.id.account_start_kakao));
                android.os.Bundle args = new android.os.Bundle();
                args.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 3);
                assertTrue(a.findViewById(com.click.designpreview.R.id.text_size_scale).performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
                assertEquals(titleSize[0] * 1.3f, title.getTextSize(), .5f);
            });
            instrumentation.waitForIdleSync();
            screenshot("signup-text-size-large");
            scenario.onActivity(a -> {
                TextView title = a.findViewById(com.click.designpreview.R.id.text_size_setup_title);
                TextView preview = a.findViewById(com.click.designpreview.R.id.text_size_preview);
                for (TextView text : new TextView[]{title, preview}) {
                    assertTrue(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                    for (int line = 0; line < text.getLineCount(); line++) assertEquals(0, text.getLayout().getEllipsisCount(line));
                }
                Rect button = new Rect();
                View next = a.findViewById(com.click.designpreview.R.id.text_size_setup_continue);
                assertTrue(next.getGlobalVisibleRect(button));
                assertEquals(next.getHeight(), button.height());
            });
            scenario.recreate();
            scenario.onActivity(a -> assertEquals(3, ((android.widget.SeekBar) a.findViewById(
                    com.click.designpreview.R.id.text_size_scale)).getProgress()));
            tap("시작하기");
            shown("핸드폰 사용을 위해\n누군가에게 도움을\n구한 적이 있나요?");
            tap("시작하기");
            shown("글자 크기\n어떻게 키우더라?");
            tap("가입하기");
            shown("회원가입");
            shown("카카오로 가입하기"); shown("번호로 가입하기");
            tap("번호로 가입하기");
            TextView title = shown("전화번호로 가입해요");
            assertEquals(30 * 1.3f * title.getResources().getDisplayMetrics().scaledDensity, title.getTextSize(), .5f);
            screenshot("signup-phone-scaled");
            try (ActivityScenario<DisplaySettingsActivity> settings = ActivityScenario.launch(DisplaySettingsActivity.class)) {
                settings.onActivity(a -> assertEquals(3, ((android.widget.SeekBar) a.findViewById(
                        com.click.designpreview.R.id.text_size_scale)).getProgress()));
                shown("아주 크게");
                screenshot("signup-shared-display-setting");
            }
        } finally { com.click.ui.common.ClickTextScale.saveLevel(context, original); }
    }

    @Test public void phoneReviewPrecedesRequiredDisplayName() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            awaitSignupRules(s);
            s.onActivity(a -> {
                ((EditText)a.findViewById(com.click.designpreview.R.id.signup_email)).setText("01012345678");
                EditText password = a.findViewById(com.click.designpreview.R.id.signup_password);
                int minimum = 4;
                String validPassword = new String(new char[minimum]).replace('\0', '7');
                password.setText(validPassword);
                ((EditText)a.findViewById(com.click.designpreview.R.id.signup_password_confirm)).setText(validPassword);
                a.findViewById(com.click.designpreview.R.id.signup_password_toggle).performClick();
                a.findViewById(com.click.designpreview.R.id.signup_password_toggle).performClick();
                assertEquals(validPassword, password.getText().toString());
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("확인해주세요");
            instrumentation.runOnMainSync(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    View title = root.findViewById(com.click.designpreview.R.id.email_confirmation_title);
                    if (title == null) continue;
                    View close = root.findViewById(com.click.designpreview.R.id.email_confirmation_close);
                    assertSame(title.getParent(), close.getParent());
                    View confirm = root.findViewById(com.click.designpreview.R.id.email_confirmation_continue);
                    Rect bounds = new Rect(); assertTrue(confirm.getGlobalVisibleRect(bounds));
                    assertEquals(confirm.getHeight(), bounds.height());
                }
            });
            screenshot("email-review");
            clickView("전화번호 수정");
            s.onActivity(a -> a.findViewById(com.click.designpreview.R.id.signup_action).performClick());
            clickView("이 전화번호로 계속"); shown("어떻게 불러드릴까요?");
            shown("이름");
            assertNoWindowText("이름 (선택)");
            assertNoWindowText("입력하지 않고 가입해도 괜찮아요.");
            s.onActivity(a -> a.findViewById(com.click.designpreview.R.id.signup_action).performClick());
            shown("이름을 1~50글자로 입력해 주세요.");
            s.onActivity(a -> {
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_name)).setText("   ");
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("이름을 1~50글자로 입력해 주세요.");
            s.onActivity(a -> ((EditText) a.findViewById(
                    com.click.designpreview.R.id.signup_name)).setText("김클릭"));
            screenshot("signup-profile-name");

        }
    }
    @Test public void accessibilityRequirementLinksToInAppGuide() throws Exception {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
                "settings delete secure enabled_accessibility_services").close();
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
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
    @Test public void loginMatchesSignupAndValidatesInline() throws Exception {
        try (ActivityScenario<LoginActivity> s = ActivityScenario.launch(LoginActivity.class)) {
            shown("로그인");
            shown("가입할 때 사용한\n전화번호와 비밀번호를 입력해 주세요.");
            shown("카카오로 로그인"); assertNoWindowText("전화번호로 로그인"); assertNoWindowText("회원가입하기");
            screenshot("login");
            s.onActivity(a -> assertEquals("Login actions have the same height",
                    a.findViewById(com.click.designpreview.R.id.login_kakao).getHeight(),
                    a.findViewById(com.click.designpreview.R.id.login_action).getHeight()));
            s.onActivity(a -> {
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_email))
                        .setText("not-an-email");
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_password))
                        .setText("short");
                a.findViewById(com.click.designpreview.R.id.login_action).performClick();
            });
            shown("전화번호를 한 번 더 확인해 주세요.");
            assertNoWindowText("가입할 때 설정한 숫자 4자리를 입력해 주세요.");
            s.onActivity(a -> {
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_email))
                        .setText("01012345678");
                ((EditText) a.findViewById(com.click.designpreview.R.id.login_password)).setText("");
                a.findViewById(com.click.designpreview.R.id.login_action).performClick();
            });
            shown("비밀번호는 숫자 4자리로 입력해 주세요.");
            assertNoWindowText("회원가입하기");
        }
    }

    @Test public void signupPasswordHintAndValidationUseLoadedServerMinimum() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            awaitSignupRules(s);
            final int[] minimum = {0};
            s.onActivity(a -> {
                EditText password = a.findViewById(com.click.designpreview.R.id.signup_password);
                minimum[0] = 4;
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_email))
                        .setText("01012345678");
                String shortPassword = new String(new char[minimum[0] - 1]).replace('\0', '7');
                password.setText(shortPassword);
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password_confirm))
                        .setText(shortPassword);
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("비밀번호는 숫자 4자리로 입력해 주세요.");
            screenshot("signup-password-minimum");
            s.onActivity(a -> {
                String validPassword = new String(new char[minimum[0]]).replace('\0', '7');
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password))
                        .setText(validPassword);
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password_confirm))
                        .setText(validPassword);
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("확인해주세요");
            clickView("전화번호 수정");
        }
    }

    @Test public void signupShowsRequiredFieldsAndRejectsLongNameWithoutTruncating() throws Exception {
        try (ActivityScenario<SignupActivity> s = ActivityScenario.launch(SignupActivity.class)) {
            awaitSignupRules(s);
            shown("전화번호 (필수)");
            shown("비밀번호 (필수)");
            s.onActivity(a -> {
                assertEquals("비밀번호는 숫자 4자리로 만들어 주세요.", ((TextView) a.findViewById(
                        com.click.designpreview.R.id.signup_password_hint_text)).getText().toString());
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_email)).setText("010-1234-5678");
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password)).setText("0123");
                ((EditText) a.findViewById(com.click.designpreview.R.id.signup_password_confirm)).setText("0123");
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("01012345678");
            clickView("이 전화번호로 계속");
            shown("이름");
            assertNoWindowText("이름은 50글자까지 입력할 수 있어요.");
            screenshot("signup-current-profile");
            s.onActivity(a -> {
                EditText name = a.findViewById(com.click.designpreview.R.id.signup_name);
                String longName = new String(new char[51]).replace('\0', '김');
                name.setText(longName);
                assertEquals(longName, name.getText().toString());
                a.findViewById(com.click.designpreview.R.id.signup_action).performClick();
            });
            shown("이름을 1~50글자로 입력해 주세요.");
            // Stop before submitting: this test must never create a real server account.
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
            shown("오늘도\n클릭과 함께\n해봐요!");
            s.onActivity(a -> assertEquals("궁금한 기능을 적거나 말해보세요!",
                    ((EditText) a.findViewById(R.id.home_request_input)).getHint().toString()));
            shown("보관함");
            assertNoWindowText("카카오톡");
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
                assertNull(a.findViewById(R.id.home_favorites_add));
                a.findViewById(R.id.home_nav_home).performClick();
            });
            SystemClock.sleep(500);
            s.onActivity(a -> {
                assertTrue(a.findViewById(R.id.home_nav_home).isSelected());
                assertEquals(0, a.findViewById(R.id.home_scroll).getScrollY());
            });
        }
    }
    @Test public void recentQuestionsCanBeSavedAndRemovedAfterRecreation() throws Exception {
        com.click.data.local.RequestLibraryStore library = new com.click.data.local.RequestLibraryStore(instrumentation.getTargetContext());
        library.record("테스트: 화면 밝기 낮춰줘", "settings.brightness.decrease");
        String id = library.entries(false).get(0).id;
        library.setFavorite(id, false);
        try (ActivityScenario<HomeActivity> s = ActivityScenario.launch(HomeActivity.class)) {
            s.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            tap("최근 질문");
            screenshot("library-recent");
            s.onActivity(a -> a.findViewById(R.id.home_favorites_scroll).findViewWithTag("saved_" + id).performClick());
            assertTrue(library.entries(true).stream().anyMatch(entry -> entry.id.equals(id)));
            tap("즐겨찾기");
            screenshot("library-saved");
            s.recreate();
            s.onActivity(a -> {
                View star = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("saved_" + id);
                assertNotNull(star); assertTrue(star.isSelected()); star.performClick();
                assertNull(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("saved_" + id));
                assertNull(a.findViewById(R.id.home_favorites_add));
            });
            assertFalse(library.entries(true).stream().anyMatch(entry -> entry.id.equals(id)));
            tap("최근 질문");
            s.onActivity(a -> assertNotNull(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("saved_" + id)));
        } finally { library.setFavorite(id, false); }
    }
    @Test public void libraryReorderMovesSavedQuestionAboveShortcutsAndPersists() throws Exception {
        com.click.data.local.RequestLibraryStore library = new com.click.data.local.RequestLibraryStore(instrumentation.getTargetContext());
        library.record("정렬 테스트 첫 질문", null);
        String id = library.entries(false).get(0).id;
        library.setFavorite(id, true);
        java.util.List<String> available = new java.util.ArrayList<>();
        com.click.data.local.HomeFavoritesStore favorites = new com.click.data.local.HomeFavoritesStore(instrumentation.getTargetContext());
        for (com.click.ui.home.HomeHelpItem item : com.click.ui.home.HomeHelpItem.values())
            if (favorites.contains(item.id)) available.add("help:" + item.id);
        for (com.click.data.local.RequestLibraryStore.Entry entry : library.entries(true)) available.add("request:" + entry.id);
        library.moveToPosition("request:" + id, 1, available, false);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            tap("순서 바꾸기");
            scenario.onActivity(a -> {
                View page = a.findViewById(R.id.home_favorites_scroll);
                assertNull(page.findViewWithTag("saved_" + id));
                View row = page.findViewWithTag("library_request:" + id);
                row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), true);
            });
            instrumentation.waitForIdleSync();
            final float[] points = new float[4];
            scenario.onActivity(a -> {
                View page = a.findViewById(R.id.home_favorites_scroll);
                View row = page.findViewWithTag("library_request:" + id);
                ViewGroup notebook = page.findViewWithTag("library_notebook");
                row.performClick();
                assertNotEquals("A tap must not reorder", "library_request:" + id, notebook.getChildAt(0).getTag());
                int[] from = new int[2], to = new int[2];
                row.getLocationOnScreen(from); notebook.getChildAt(0).getLocationOnScreen(to);
                points[0] = from[0] + row.getWidth() / 2f; points[1] = from[1] + row.getHeight() / 2f;
                points[2] = points[0]; points[3] = to[1] + 8;
            });
            long down = SystemClock.uptimeMillis();
            sendTouch(down, MotionEvent.ACTION_DOWN, points[0], points[1]);
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 200);
            float sideways = 48 * instrumentation.getTargetContext().getResources().getDisplayMetrics().density;
            sendTouch(down, MotionEvent.ACTION_MOVE, points[0] + sideways, points[1]);
            scenario.onActivity(a -> {
                View row = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id);
                assertEquals("A sideways finger movement must not move the row", 0f, row.getTranslationX(), .5f);
                assertEquals(0f, row.getTranslationY(), .5f);
            });
            for (int step = 1; step <= 20; step++) {
                float fraction = step / 20f;
                sendTouch(down, MotionEvent.ACTION_MOVE, points[0] + sideways,
                        points[1] + (points[3] - points[1]) * fraction);
                SystemClock.sleep(20);
            }
            scenario.onActivity(a -> {
                View row = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id);
                assertEquals(0f, row.getTranslationX(), .5f);
                assertTrue("A held row must follow vertical movement", row.getTranslationY() < 0);
            });
            screenshot("library-reorder-vertical-drag");
            sendTouch(down, MotionEvent.ACTION_UP, points[0] + sideways, points[3]);
            SystemClock.sleep(300);
            instrumentation.waitForIdleSync();
            scenario.onActivity(a -> {
                View page = a.findViewById(R.id.home_favorites_scroll);
                ViewGroup notebook = page.findViewWithTag("library_notebook");
                assertEquals("library_request:" + id, notebook.getChildAt(0).getTag());
            });
            screenshot("library-reorder");
            scenario.recreate();
            shown("완료"); tap("완료");
            scenario.onActivity(a -> {
                View page = a.findViewById(R.id.home_favorites_scroll);
                assertNotNull(page.findViewWithTag("saved_" + id));
                ViewGroup notebook = page.findViewWithTag("library_notebook");
                assertEquals("library_request:" + id, notebook.getChildAt(0).getTag());
                a.findViewById(R.id.home_nav_home).performClick();
                View homeRow = a.findViewById(R.id.home_scroll).findViewWithTag("library_request:" + id);
                assertEquals(0, ((ViewGroup) homeRow.getParent()).indexOfChild(homeRow));
            });
        } finally { library.setFavorite(id, false); }
    }

    @Test public void libraryHelpExplainsGesturesAndKeepsActionsVisible() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            for (String tab : new String[]{"즐겨찾기", "최근 질문"}) {
                tap(tab);
                scenario.onActivity(a -> {
                    View help = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_help");
                    Rect bounds = new Rect(); assertTrue(help.getGlobalVisibleRect(bounds));
                    assertEquals(help.getHeight(), bounds.height());
                    assertEquals(help.getWidth(), bounds.width());
                    help.performClick();
                });
                shown("보관함 사용 방법"); shown("최근 질문 지우기");
                screenshot("library-help");
                instrumentation.runOnMainSync(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (find(root, "보관함 사용 방법") == null) continue;
                        View confirm = root.findViewById(android.R.id.button1);
                        Rect bounds = new Rect(); assertTrue(confirm.getGlobalVisibleRect(bounds));
                        assertEquals(confirm.getHeight(), bounds.height());
                        root.findViewWithTag("click_dialog_close").performClick();
                    }
                });
                assertNoWindowText("보관함 사용 방법");
            }
            screenshot("library-help-header");
        }
    }

    @Test public void libraryAccessibilityOpensGuide() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            TextView label = shown("접근성 설정");
            instrumentation.runOnMainSync(() -> {
                View launch = label;
                while (!launch.isClickable()) launch = (View) launch.getParent();
                launch.performClick();
            });
            shown("접근성 권한이 필요해요");
        }
    }

    @Test public void recentSwipeRevealsDeleteAndKeepsSavedFavorite() throws Exception {
        com.click.data.local.RequestLibraryStore library = new com.click.data.local.RequestLibraryStore(instrumentation.getTargetContext());
        library.record("밀어서 지울 테스트 질문", null);
        String id = library.entries(false).get(0).id;
        library.setFavorite(id, true);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.home_nav_favorites).performClick());
            tap("최근 질문");
            final float[] points = new float[4];
            scenario.onActivity(a -> {
                View row = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id);
                row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), true);
            });
            instrumentation.waitForIdleSync();
            scenario.onActivity(a -> {
                View row = a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id);
                assertNull(find(row, android.text.format.DateFormat.format("M월 d일 · HH:mm", library.entries(false).get(0).timestamp).toString()));
                int[] location = new int[2]; row.getLocationOnScreen(location);
                points[0] = location[0] + row.getWidth() * .75f; points[1] = location[1] + row.getHeight() / 2f;
                points[2] = location[0] + row.getWidth() * .2f; points[3] = points[1];
            });
            swipe(points, false);
            shown("삭제");
            assertTrue(library.entries(false).stream().anyMatch(entry -> entry.id.equals(id)));
            screenshot("library-swipe-delete");
            clickView("삭제");
            assertFalse(library.entries(false).stream().anyMatch(entry -> entry.id.equals(id)));
            scenario.recreate();
            scenario.onActivity(a -> assertNull(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id)));
            tap("즐겨찾기");
            scenario.onActivity(a -> assertNotNull(a.findViewById(R.id.home_favorites_scroll).findViewWithTag("library_request:" + id)));
        } finally { library.setFavorite(id, false); library.removeRecent(id); }
    }

    private void swipe(float[] points, boolean longPress) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, points[0], points[1]);
        if (longPress) SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 200);
        for (int step = 1; step <= 20; step++) {
            float fraction = step / 20f;
            sendTouch(down, MotionEvent.ACTION_MOVE, points[0] + (points[2] - points[0]) * fraction,
                    points[1] + (points[3] - points[1]) * fraction);
            SystemClock.sleep(20);
        }
        sendTouch(down, MotionEvent.ACTION_UP, points[2], points[3]);
        SystemClock.sleep(300);
    }

    private void sendTouch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .injectInputEvent(event, true));
        event.recycle();
    }

    @Test public void accountConfirmationsShowClueAndCancelSafely() throws Exception {
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            for (String label : new String[]{"로그아웃", "회원 탈퇴"}) {
                TextView item = shown(label);
                instrumentation.runOnMainSync(() -> {
                    View row = item;
                    while (!row.isClickable()) row = (View) row.getParent();
                    row.performClick();
                });
                String title = label.equals("로그아웃") ? "로그아웃할까요?" : "회원 탈퇴를 진행할까요?";
                shown(title);
                instrumentation.runOnMainSync(() -> {
                    boolean found = false;
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (root.findViewWithTag("account_goodbye_clue") == null) continue;
                        found = true;
                        assertNotNull(root.findViewWithTag("click_dialog_close"));
                        View cancel = root.findViewById(android.R.id.button2);
                        assertEquals(0f, cancel.getElevation(), 0f);
                        assertNull(cancel.getStateListAnimator());
                        Rect bounds = new Rect(); assertTrue(cancel.getGlobalVisibleRect(bounds));
                        assertEquals(cancel.getHeight(), bounds.height());
                    }
                    assertTrue(found);
                });
                assertNoWindowText("잠시 이용을 마치시나요?");
                assertNoWindowText("탈퇴 전에 꼭 확인해 주세요");
                screenshot(label.equals("로그아웃") ? "logout-clue" : "delete-clue");
                clickView("취소");
                assertNoWindowText(title);
                shown("설정");
            }
        }
    }

    @Test public void deleteRequiresReadingToTheEndEveryTime() throws Exception {
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                TextView deleteItem = shown("회원 탈퇴");
                instrumentation.runOnMainSync(() -> {
                    View row = deleteItem;
                    while (!row.isClickable()) row = (View) row.getParent();
                    row.performClick();
                });
                shown("회원 탈퇴를 진행할까요?");
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View dialogRoot = null;
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (root.findViewWithTag("account_goodbye_clue") != null) dialogRoot = root;
                    }
                    assertNotNull(dialogRoot);
                    View confirm = dialogRoot.findViewById(android.R.id.button1);
                    android.widget.ScrollView body = dialogRoot.findViewWithTag("click_dialog_body_scroll");
                    assertFalse(confirm.isEnabled());
                    assertTrue("The notice must be scrollable", body.canScrollVertically(1));
                    int end = body.getChildAt(0).getHeight() - body.getHeight();
                    body.scrollTo(0, end / 2);
                    assertFalse("Partial reading must not enable deletion", confirm.isEnabled());
                    body.scrollTo(0, end);
                    assertTrue("Reaching the end enables deletion", confirm.isEnabled());
                    body.scrollTo(0, 0);
                    assertTrue("Reading remains complete", confirm.isEnabled());
                });
                if (attempt == 0) screenshot("delete-read-complete");
                clickView("취소");
            }
            TextView logoutItem = shown("로그아웃");
            instrumentation.runOnMainSync(() -> {
                View row = logoutItem;
                while (!row.isClickable()) row = (View) row.getParent();
                row.performClick();
            });
            shown("로그아웃할까요?");
            instrumentation.runOnMainSync(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    if (root.findViewWithTag("account_goodbye_clue") != null)
                        assertTrue(root.findViewById(android.R.id.button1).isEnabled());
                }
            });
            clickView("취소");
        }
    }

    @Test public void settingsDialogsHaveCloseAndClueOpensItsOwnPage() throws Exception {
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            for (String label : new String[]{"앱 권한 확인", "자동으로 도와주기", "클릭 개선 참여", "서비스 이용약관", "개인정보 처리방침"}) {
                TextView item = shown(label);
                instrumentation.runOnMainSync(() -> {
                    View row = item;
                    while (!row.isClickable()) row = (View) row.getParent();
                    row.performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View close = null;
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        View candidate = root.findViewWithTag("click_dialog_close");
                        if (candidate != null && candidate.isShown()) {
                            assertNull(find(root, "클릭"));
                            close = candidate;
                        }
                    }
                    assertNotNull(close);
                    Rect bounds = new Rect(); assertTrue(close.getGlobalVisibleRect(bounds));
                    assertEquals(close.getHeight(), bounds.height());
                    close.performClick();
                });
            }
            TextView about = shown("클루에 대해서");
            instrumentation.runOnMainSync(() -> {
                View row = about; while (!row.isClickable()) row = (View) row.getParent(); row.performClick();
            });
            shown("안녕하세요, 클루예요!"); shown("3세 · 호기심 많은 펭귄");
            screenshot("about-clue");
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            shown("설정");
        }
    }

    @Test public void menuMatchesProfileCardReference() throws Exception {
        try (ActivityScenario<MenuActivity> s = ActivityScenario.launch(MenuActivity.class)) {
            shown("설정");
            shown("앱 권한 확인");
            shown("클릭 개선 참여");
            shown("클릭 사용법");
            shown("서비스 이용약관");
            shown("개인정보 처리방침");
            shown("로그아웃");
            shown("회원 탈퇴");
            screenshot("menu-current");
            s.onActivity(activity -> {
                TextView logout = (TextView) find(activity.getWindow().getDecorView(), "로그아웃");
                View ancestor = logout;
                while (!(ancestor instanceof android.widget.ScrollView)) ancestor = (View) ancestor.getParent();
                ((android.widget.ScrollView) ancestor).fullScroll(View.FOCUS_DOWN);
            });
            screenshot("menu-logout-icon");
        }
    }
    @Test public void finalConsentGuidePageOpensAccessibilitySettings() throws Exception {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                new android.content.IntentFilter(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try (ActivityScenario<PermissionGuideActivity> s = ActivityScenario.launch(PermissionGuideActivity.class)) {
            s.onActivity(a -> {
                for (int i = 0; i < 4; i++) {
                    a.findViewById(com.click.designpreview.R.id.guide_action).performClick();
                }
            });
            shown("‘사용 중’이면 설정 완료예요");
            screenshot("permission-consent");
            s.onActivity(a -> a.findViewById(
                    com.click.designpreview.R.id.guide_action).performClick());
            assertEquals("접근성 설정 Intent를 열어야 합니다.", 1, monitor.getHits());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }
    @Test public void postAccessibilityScreensShareClickBrand() throws Exception {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
                "settings put secure enabled_accessibility_services com.click/com.click.service.accessibility.ClickAccessibilityService").close();
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
                "settings put secure accessibility_enabled 1").close();
        SystemClock.sleep(500);
        try (ActivityScenario<AccessibilityHelpActivity> s = ActivityScenario.launch(
                AccessibilityHelpActivity.class)) {
            shown("접근성 설정이\n완료됐어요");
            shown("클릭 시작하기");
            screenshot("accessibility-complete");
        }
        try (ActivityScenario<HomeActivity> s = ActivityScenario.launch(HomeActivity.class)) {
            shown("오늘도\n클릭과 함께\n해봐요!");
            shown("보관함");
            shown("클릭 튜토리얼");
            screenshot("home");
        }
        try (ActivityScenario<MenuActivity> s = ActivityScenario.launch(MenuActivity.class)) {
            shown("설정");
            shown("앱 권한 확인");
            shown("클릭 개선 참여");
            shown("클릭 사용법");
            shown("로그아웃");
            screenshot("menu");
        }
        if (!com.click.core.permission.PermissionStatus.isAccessibilityEnabled(instrumentation.getTargetContext())) {
            instrumentation.getTargetContext().startActivity(new android.content.Intent(instrumentation.getTargetContext(),
                    OverlayTutorialActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            shown(instrumentation.getTargetContext().getString(R.string.tour_display_permission));
            screenshot("overlay-tutorial-permission");
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
        assertTrue(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(down, true));
        assertTrue(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(up, true)); down.recycle(); up.recycle();
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
        Bitmap image = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }
}
