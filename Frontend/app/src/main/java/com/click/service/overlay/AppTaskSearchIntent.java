package com.click.service.overlay;

import java.util.regex.*;

/** A product/search phrase supplied by the user, never a generated product or an order approval. */
final class AppTaskSearchIntent {
    private static final Pattern FIELD_INPUT = Pattern.compile(
            "^(?:[^\\s]{1,30}(?:\\s*앱)?에서\\s*)?(?:일정\\s*)?(검색창|검색란|검색어|제목|장소|메모)(?:\\s*칸)?에(?:는)?\\s*"
            + "(.{1,80}?)\\s*(?:입력|적어|써)(?:해|줘|하기|하고|주세요|싶어|싶어요|요|\\s|[.!?])*$");
    static String explicitInput(String goal, String field) {
        if (goal == null || goal.matches("(?s).*(?:말고|하지|그리고|그다음|→).*")) return null;
        Matcher match = FIELD_INPUT.matcher(goal.trim());
        if (!match.matches() || !match.group(1).startsWith(field)) return null;
        String value = userValue(match.group(2));
        return value.isEmpty() || value.matches("검색어|내용|키워드") ? null : value;
    }
    private static String userValue(String phrase) {
        String value = phrase.trim();
        int last = value.length() - 1;
        if (last > 0 && "을를".indexOf(value.charAt(last)) >= 0) {
            char previous = value.charAt(last - 1);
            boolean hangul = previous >= '가' && previous <= '힣';
            // Keep words such as 가을 and 마을 when the final syllable is part of the word.
            if (!hangul || ((previous - '가') % 28 == 0) == (value.charAt(last) == '를')) {
                value = value.substring(0, last).trim();
            }
        }
        return value.replaceFirst("^[‘“'\"](.*)[’”'\"]$", "$1").trim();
    }
    static String query(String goal,String appLabel){
        if(goal==null||goal.matches("(?s).*(?:취소|환불|반품|배송|주문내역|주문 내역|결제|말고|하지|그리고|그다음|→).*"))return null;
        String text=goal.trim().replaceAll("즐겨\\s+찾기","즐겨찾기");
        if(appLabel==null&&text.contains("에서"))return null;
        if(appLabel!=null)text=text.replaceFirst("(?i)^"+Pattern.quote(RequestedApp.shortName(appLabel))+"\\s*(?:에서|으로|에|을|를)?\\s*","");
        Matcher m=Pattern.compile("^(.{1,80}?)\\s*(?:검색|(?<!즐겨)(?:찾아|찾기)|주문|구매|사고)(?:해|줘|하기|하고|해줘|주세요|싶어|싶어요|싶다|싶은데|요|좀|\\s|[.!?])*?$",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!m.matches())return null;
        String query=userValue(m.group(1));
        return query.isEmpty()||query.matches(".*(?:열어|실행|로그인|인증|보내|삭제).*" )?null:query;
    }
}
