package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User requirements, kept separate from execution steps and screen observations. */
public final class RequirementReview {
    private String request;
    private String target;
    private String scope;
    private String direction;
    private boolean blocked;
    private final boolean font;
    private JSONObject otherGoal;
    private String otherLabel;
    private boolean confirmed;

    public RequirementReview(String request) {
        this(request, isFontRequest(request));
    }
    private RequirementReview(String request, boolean fontRequest) {
        this.request = request;
        font = fontRequest;
        update(request);
    }
    public static RequirementReview forResolvedGoal(String request, JSONObject goal, String label) {
        return forResolvedGoal(request, goal, label, null);
    }
    public static RequirementReview forResolvedGoal(String request, JSONObject goal, String label, RequirementReview previous) {
        if (!goal.optString("goal_type").startsWith("settings.font_size.")) return forGoal(request, goal, label);
        RequirementReview review = previous != null && previous.isFont() && request.equals(previous.request)
                ? previous : new RequirementReview(request, true);
        // The model may suggest a direction; scope remains a separate user requirement.
        // Named apps and missing scope must never silently become a system-wide change.
        JSONObject slots = goal.optJSONObject("slots");
        String direction = slots == null ? "" : slots.optString("direction");
        if (review.direction == null && !review.blocked && ("increase".equals(direction) || "decrease".equals(direction)))
            review.direction = direction;
        return review;
    }
    public static boolean isFontRequest(String text) {
        String n = GoalCatalogClassifier.normalize(text);
        return n.contains("글자") || n.contains("글씨") || n.contains("폰트");
    }
    /** Settings context can prefill the scope, but never confirms or overrides an app request. */
    public static RequirementReview forContext(String request, String packageName) {
        RequirementReview review = new RequirementReview(request);
        if (("com.android.settings".equals(packageName) || "com.samsung.android.settings".equals(packageName))
                && review.needsTarget() && !review.appOnly() && namedApp(request) == null
                && !review.blocked) review.choose("phone");
        return review;
    }
    public static RequirementReview forGoal(String request, JSONObject goal, String label) {
        RequirementReview review = new RequirementReview(request);
        if (review.isFont() && "android_settings".equals(goal.optString("target_app"))
                && !review.blocked && namedApp(request) == null && !review.appOnly()) {
            review.choose("phone");
            JSONObject slots = goal.optJSONObject("slots");
            if (review.needsDirection() && slots != null) review.choose(slots.optString("direction"));
        }
        review.otherGoal = goal;
        review.otherLabel = label;
        if (!review.isFont()) review.target = "android_settings".equals(goal.optString("target_app")) ? "휴대폰 설정"
                : "coupang".equals(goal.optString("target_app")) ? "쿠팡" : goal.optString("target_app");
        return review;
    }
    public void update(String answer) {
        if (answer == null || answer.trim().isEmpty()) return;
        confirmed = false;
        if (!answer.equals(request)) request += "\n추가 확인: " + answer.trim();
        String n = GoalCatalogClassifier.normalize(answer);
        boolean negative = n.matches(".*(하지마|하지말|지마|지말|지않|안해|말아).*");
        if (negative) blocked = true;
        if (n.contains("다른뜻") || n.contains("모르겠") || n.equals("아니") || n.equals("아니요")) {
            target = null; scope = null; direction = null; blocked = false; return;
        }
        if (!font) return;
        boolean up = n.matches(".*(크게|키워|키우|확대|늘려|너무작|작아서).*");
        boolean down = n.matches(".*(작게|줄여|줄이|축소|너무커|커서).*");
        if (up || down) { direction = up == down ? null : up ? "increase" : "decrease"; if (!negative) blocked = false; }
        String app = namedApp(answer);
        boolean phone = n.contains("휴대폰전체") || n.contains("핸드폰전체") || n.contains("스마트폰전체")
                || n.contains("시스템전체") || n.contains("모든앱") || n.contains("기본글자") || n.contains("휴대폰설정")
                || n.contains("핸드폰설정") || n.contains("시스템글자")
                || app == null && (n.equals("설정") || n.contains("설정에서") || n.contains("설정앱"));
        if (app == null && !phone && appOnly() && target == null && !isFontRequest(answer)
                && !up && !down && !negative && !n.matches(".*(앱|모르|맞아|네|아니|시작|변경).*") && answer.length() < 40)
            app = answer.trim();
        if (app != null && phone) { target = null; scope = null; return; }
        if (app != null) { target = app; scope = "앱 안에서만"; }
        else if (phone) { target = "휴대폰 설정"; scope = "휴대폰 기본 글자 크기"; }
        else if (n.contains("특정앱") || n.equals("앱안에서만") || n.contains("이앱") || n.contains("지금보는앱")) {
            target = null; scope = "앱 안에서만";
        }
        // '전체' alone must never silently turn an app request into a system setting.
        if (target != null && !isPhone() && (n.contains("전체") || n.contains("모든글자"))) scope = "앱 전체 글자";
        if (target != null && !isPhone() && (n.contains("채팅방") || n.contains("대화방"))) scope = "채팅방 글자";
    }
    private static String namedApp(String answer) {
        String n = GoalCatalogClassifier.normalize(answer);
        String[][] names = {{"카카오톡", "카카오톡"}, {"카톡", "카카오톡"}, {"유튜브", "유튜브"},
                {"youtube", "유튜브"}, {"크롬", "Chrome"}, {"chrome", "Chrome"}, {"네이버", "네이버"},
                {"쿠팡", "쿠팡"}, {"인스타", "Instagram"}, {"삼성인터넷", "삼성 인터넷"}, {"삼성노트", "삼성 노트"}};
        for (String[] name : names) if (n.contains(name[0])) return name[1];
        Matcher app = Pattern.compile("([가-힣A-Za-z0-9]+)\\s*앱(?:에서|의|안|만|\\s|$)").matcher(answer);
        if (app.find() && !app.group(1).matches("특정|이|모든|전체|어떤|다른")) return app.group(1);
        app = Pattern.compile("([가-힣A-Za-z0-9]+)에서").matcher(answer);
        if (app.find() && !app.group(1).matches("설정|휴대폰|핸드폰|스마트폰|시스템|전체|앱|안")) return app.group(1);
        return null;
    }
    public void choose(String choice) {
        confirmed = false; blocked = false;
        if ("phone".equals(choice)) { target = "휴대폰 설정"; scope = "휴대폰 기본 글자 크기"; }
        if ("app".equals(choice)) { target = null; scope = "앱 안에서만"; }
        if ("increase".equals(choice) || "decrease".equals(choice)) direction = choice;
    }
    public boolean isFont() { return font; }
    public boolean isPhone() { return "휴대폰 설정".equals(target); }
    public boolean needsTarget() { return font && target == null; }
    public boolean needsDirection() { return font && direction == null; }
    public boolean appOnly() { return font && "앱 안에서만".equals(scope); }
    public String request() { return request; }
    /** Preserve the request and explicit selections when retrying server preparation. */
    public String preparationSource() {
        String source = request;
        if (isPhone()) source += "\n사용자가 선택한 범위: 휴대폰 전체";
        else if (appOnly()) source += "\n사용자가 선택한 범위: 특정 앱";
        if (font && direction != null) source += "\n사용자가 선택한 변경: " + change();
        return source;
    }
    public String summary() {
        if (!font) return otherLabel == null ? request : otherLabel;
        return (target == null ? "대상 확인 필요" : target) + " · " + change();
    }
    private String change() { return direction == null ? "크게 / 작게 확인 필요" : "increase".equals(direction) ? "글자를 더 크게" : "글자를 더 작게"; }
    public JSONArray rows() throws JSONException {
        JSONArray rows = new JSONArray();
        row(rows, "대상", target);
        if (font) row(rows, "적용 범위", scope);
        row(rows, "변경 내용", font ? (direction == null ? null : change()) : otherLabel);
        return rows;
    }
    private void row(JSONArray rows, String label, String value) throws JSONException {
        rows.put(new JSONObject().put("label", label + " · " + (value == null ? "확인 필요" : value))
                .put("status", value == null ? "pending" : confirmed ? "completed" : "identified"));
    }
    public JSONObject proposedGoal(GoalCatalogClassifier catalog, AgentServerContract server) throws JSONException {
        if (blocked || server == null || !server.supports("start_task")) return null;
        if (!font) return otherGoal != null && server.supportsGoal(otherGoal.optString("goal_type")) ? otherGoal : null;
        if (!isPhone() || direction == null || catalog == null) return null;
        GoalCatalogClassifier.Match match = catalog.executableGoal("settings.font_size." + direction);
        return match != null && server.supportsGoal(match.goalType) ? match.toTypedGoalJson() : null;
    }
    public JSONObject confirm(GoalCatalogClassifier catalog, AgentServerContract server) throws JSONException {
        JSONObject goal = proposedGoal(catalog, server);
        confirmed = goal != null;
        return goal;
    }
    public boolean confirmed() { return confirmed; }
    public String prompt(boolean executable) {
        if (blocked) return "변경을 원하시는지 다시 알려주세요. 아직 시작하지 않았어요.";
        if (needsTarget()) {
            String question = appOnly()
                    ? "어느 앱의 글자를 바꿀까요? 앱 이름을 알려주세요. 휴대폰 전체 글자를 바꾸려면 휴대폰 기본 글자를 눌러주세요."
                    : "어디의 글자를 바꿀까요? 휴대폰 기본 글자, 특정 앱 안에서만 중에서 선택해 주세요.";
            if (needsDirection()) question += " 글자 크기는 더 크게, 더 작게 중에서 선택해 주세요.";
            return question;
        }
        if (needsDirection()) return "글자 크기를 어떻게 바꿀까요? 더 크게, 더 작게 중에서 선택해 주세요.";
        if (font && !isPhone()) return target + "의 글자 변경 안내는 아직 지원하지 않아요. 내용을 수정하거나 요청을 중지해 주세요.";
        if (!executable) return "이 요청의 실행 안내는 아직 지원하지 않아요. 내용을 수정할 수 있어요.";
        return font ? "휴대폰 기본 글자를 " + ("increase".equals(direction) ? "더 크게" : "더 작게")
                + " 바꾸려는 거죠? 앱에 따라 적용되지 않을 수 있어요. 맞으면 이 내용으로 시작을 눌러주세요."
                : summary() + ". 원하신 내용이 맞으면 이 내용으로 시작을 눌러주세요.";
    }
}
