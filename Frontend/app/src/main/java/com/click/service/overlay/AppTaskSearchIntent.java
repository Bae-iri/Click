package com.click.service.overlay;

import java.util.regex.*;

/** A product/search phrase supplied by the user, never a generated product or an order approval. */
final class AppTaskSearchIntent {
    static String query(String goal,String appLabel){
        if(goal==null||appLabel==null||goal.matches("(?s).*(?:취소|환불|반품|배송|주문내역|주문 내역|결제|말고|하지|그리고|그다음|→).*"))return null;
        String text=goal.trim().replaceAll("즐겨\\s+찾기","즐겨찾기").replaceFirst("(?i)^"+Pattern.quote(RequestedApp.shortName(appLabel))+"\\s*(?:에서|으로|에|을|를)?\\s*","");
        Matcher m=Pattern.compile("^(.{1,80}?)\\s*(?:검색|(?<!즐겨)(?:찾아|찾기)|주문|구매|사고)(?:해|줘|하기|하고|해줘|주세요|싶어|싶어요|싶다|싶은데|요|좀|\\s|[.!?])*?$",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!m.matches())return null;
        String query=m.group(1).trim().replaceFirst("(?:을|를)$","").trim();
        return query.isEmpty()||query.matches(".*(?:열어|실행|로그인|인증|보내|삭제).*" )?null:query;
    }
}
