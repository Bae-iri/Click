package com.click.domain.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** High-volume utterance regression across age, tone, spacing, punctuation, and app context. */
public class GoalCatalogPersonaTest {
    private static final List<String> PERSONA_TEMPLATES = List.of(
            "{}",
            "저기요, {} 좀 해 주세요",
            "야 {} 해줘",
            "클릭아 {} 부탁해",
            "엄마 폰에서 {} 부탁한다",
            "아빠가 잘 몰라서 그런데 {} 해 주실래요",
            "할머니가 쓰기 편하게 {} 부탁해요",
            "손이 불편해서 {} 해주세요",
            "제가 말이 좀 서툰데 {} 도와줘요",
            "혹시 {} 가능한가요?",
            "급해요 {} 바로 부탁드립니다",
            "{} 하고 싶은데 도와줘",
            "클릭, {} 좀",
            "please {}",
            "PLEASE {} NOW",
            "음... {} 해줄래",
            "제가 원하는 건 {} 입니다",
            "그러니까 말이야 {} 해 줘",
            "{}!!!",
            "  {}  "
    );

    @Test public void everyKeywordCombinationSurvivesManyPersonasAndContexts() throws Exception {
        JSONObject root = loadRoot();
        GoalCatalogClassifier classifier = GoalCatalogClassifier.fromJson(root.toString());
        JSONArray goals = root.getJSONArray("goals");
        int[] checked = {0};
        List<String> failures = new ArrayList<>();

        for (int goalIndex = 0; goalIndex < goals.length(); goalIndex++) {
            JSONObject goal = goals.getJSONObject(goalIndex);
            String expected = goal.getString("goal_type");
            String targetApp = goal.getString("target_app");
            String matchingForeground = "android_settings".equals(targetApp)
                    ? "com.android.settings" : "kr.go.minwon.m";
            String otherForeground = "android_settings".equals(targetApp)
                    ? "kr.go.minwon.m" : "com.android.settings";

            for (String base : combinations(goal.getJSONArray("keyword_groups"))) {
                List<String> variants = new ArrayList<>();
                for (String template : PERSONA_TEMPLATES) variants.add(template.replace("{}", base));
                variants.add(base.toUpperCase(java.util.Locale.ROOT));
                variants.add(base.replace(" ", "     "));
                variants.add(punctuate(base));

                for (String utterance : variants) {
                    recordGoal(failures, classifier, expected, utterance, matchingForeground);
                    recordGoal(failures, classifier, expected, utterance, null);
                    recordGoal(failures, classifier, expected, utterance, otherForeground);
                    checked[0] += 3;
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("expected a broad persona corpus but checked " + checked[0], checked[0] >= 90_000);
    }

    @Test public void naturalUtterancesMapToCanonicalGoals() throws Exception {
        GoalCatalogClassifier classifier = GoalCatalogClassifier.fromJson(loadRoot().toString());
        String settings = "com.android.settings";
        String coupang = "com.coupang.mobile";
        Object[][] cases = {
                {"settings.font_size.increase", "엄마가 글씨가 너무 작아서 잘 안 보여, 크게 해 줄래?", settings},
                {"settings.font_size.increase", "폰트 사이즈 좀 키워 주세요", settings},
                {"settings.font_size.increase", "text size bigger please", settings},
                {"settings.font_size.decrease", "글자가 너무 커서 화면을 가려, 작게 해줘", settings},
                {"settings.font_size.decrease", "폰트 크기 축소 부탁드립니다", settings},
                {"settings.wifi.enable", "와이파이 좀 켜 줘", settings},
                {"settings.wifi.share", "지금 연결된 wifi QR로 공유해줘", settings},
                {"settings.bluetooth.connect", "블루투스 이어폰 페어링 해줘", settings},
                {"settings.nfc.enable", "엔에프씨 사용하게 켜줘", settings},
                {"settings.do_not_disturb.enable", "회의 중이니까 방해 금지 모드 설정해", settings},
                {"settings.auto_rotate.disable", "누워서 보게 화면 회전 잠가줘", settings},
                {"settings.auto_rotate.enable", "화면 회전 잠금 해제해줘", settings},
                {"settings.eye_comfort.enable", "밤이라 눈 아파, 블루라이트 기능 켜줘", settings},
                {"settings.adaptive_brightness.disable", "밝기 자동 조절 해제해줘", settings},
                {"settings.data_saver.enable", "데이터 아끼기 모드 켜 주세요", settings},
                {"settings.data_roaming.disable", "해외 로밍 데이터 차단해", settings},
                {"settings.vpn.connect", "브이피엔에 접속해줘", settings},
                {"settings.vpn.disconnect", "VPN 연결 해제해줘", settings},
                {"settings.private_dns.change", "개인 DNS 설정 바꿔줘", settings},
                {"settings.hotspot.password.change", "테더링 비밀번호 변경해", settings},
                {"settings.battery_percentage.enable", "배터리 숫자 보이게 해줘", settings},
                {"settings.app_cache.clear", "어플 캐시 데이터 비워줘", settings},
                {"settings.notification_history.view", "아까 온 지난 알림 내역 보여줘", settings},
                {"settings.color_inversion.enable", "화면 색 반전 기능 활성화", settings},
                {"settings.captions.enable", "라이브 캡션 켜 주세요", settings},
                {"settings.mono_audio.enable", "한쪽 이어폰 소리 때문에 모노 오디오 설정해", settings},
                {"settings.software_update.check", "시스템 업데이트 있는지 검사해줘", settings},
                {"settings.device_name.change", "휴대폰 이름 변경하고 싶어", settings},
                {"gov24.login", "정부24 로그인 도와줘", "kr.go.minwon.m"},
                {"gov24.resident_copy.open", "정부24 주민등록등본 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.resident_extract.open", "정부24 주민등록초본 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.move_in.open", "정부24 전입신고 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.building_register.open", "정부24 건축물대장 열람 화면 보여줘", "kr.go.minwon.m"},
                {"gov24.land_register.open", "정부24 토지대장 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.land_use_plan.open", "정부24 토지이용계획 확인 화면 보여줘", "kr.go.minwon.m"},
                {"gov24.local_tax_payment.open", "정부24 지방세 납세증명서 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.local_tax_assessment.open", "정부24 지방세 세목별 과세증명 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.national_tax_payment.open", "정부24 국세 납세증명서 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.income_certificate.open", "정부24 소득금액증명 발급 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.vehicle_register.open", "정부24 자동차등록원부 열람 화면 보여줘", "kr.go.minwon.m"},
                {"gov24.resident_card.reissue.open", "정부24 주민등록증 재발급 신청 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.passport.reissue.open", "정부24 여권 재발급 신청 화면 찾아줘", "kr.go.minwon.m"},
                {"gov24.application.history.view", "정부24 나의 신청 내역 보여줘", "kr.go.minwon.m"},
                {"gov24.application.status.view", "정부24 민원 처리 상태 확인하고 싶어", "kr.go.minwon.m"},
                {"gov24.documents.issued.view", "정부24 증명서 발급 내역 보여줘", "kr.go.minwon.m"},
                {"gov24.documents.electronic.view", "정부24 전자문서지갑 열어줘", "kr.go.minwon.m"},
                {"gov24.benefits.view", "정부24 보조금24 혜택 확인하고 싶어", "kr.go.minwon.m"},
                {"gov24.service.search", "정부24 민원서비스 검색 화면 열어줘", "kr.go.minwon.m"},
        };
        for (Object[] item : cases) {
            assertGoal(classifier, (String) item[0], (String) item[1], (String) item[2]);
        }
    }

    private static void assertGoal(GoalCatalogClassifier classifier, String expected,
                                   String utterance, String foreground) {
        GoalCatalogClassifier.Classification result = classifier.classify(utterance, foreground);
        assertFalse("ambiguous: " + utterance, result.ambiguous);
        assertNotNull("unmatched: " + utterance, result.match);
        assertEquals("utterance: " + utterance, expected, result.match.goalType);
    }

    private static void recordGoal(List<String> failures, GoalCatalogClassifier classifier,
                                   String expected, String utterance, String foreground) {
        GoalCatalogClassifier.Classification result = classifier.classify(utterance, foreground);
        String actual = result.ambiguous ? "AMBIGUOUS"
                : result.match == null ? "UNMATCHED" : result.match.goalType;
        if (!expected.equals(actual) && failures.size() < 100) {
            failures.add("expected=" + expected + " actual=" + actual
                    + " foreground=" + foreground + " utterance=" + utterance);
        }
    }

    private static List<String> combinations(JSONArray groups) throws Exception {
        List<String> result = new ArrayList<>();
        addCombination(groups, 0, new ArrayList<>(), result);
        return result;
    }

    private static void addCombination(JSONArray groups, int index, List<String> parts,
                                       List<String> result) throws Exception {
        if (index == groups.length()) {
            result.add(String.join(" ", parts));
            return;
        }
        JSONArray group = groups.getJSONArray(index);
        for (int keyword = 0; keyword < group.length(); keyword++) {
            parts.add(group.getString(keyword));
            addCombination(groups, index + 1, parts, result);
            parts.remove(parts.size() - 1);
        }
    }

    private static String punctuate(String source) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (!Character.isWhitespace(current) && value.length() > 0) value.append(index % 2 == 0 ? '.' : '-');
            value.append(current);
        }
        return value.toString();
    }

    private static JSONObject loadRoot() throws Exception {
        Path[] candidates = {
                Path.of("..", "..", "AI-Agent", "goal_catalog", "mvp_goals.json"),
                Path.of("..", "AI-Agent", "goal_catalog", "mvp_goals.json"),
                Path.of("AI-Agent", "goal_catalog", "mvp_goals.json")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new JSONObject(new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8));
            }
        }
        throw new IllegalStateException("shared goal catalog not found from " + Path.of("").toAbsolutePath());
    }
}
