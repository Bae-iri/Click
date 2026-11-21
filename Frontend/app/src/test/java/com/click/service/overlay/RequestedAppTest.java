package com.click.service.overlay;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestedAppTest {
    @Test public void blockingUsesTheSubjectAndKeepsPrivateEntryOutOfTheDialpad(){
        assertTrue(RequestedApp.isNumberBlock("전화번호 차단하고 싶어"));
        assertFalse(RequestedApp.needsDialpad("전화번호 차단하고 싶어"));
        assertTrue(RequestedApp.savedContactBlock("저장된 연락처를 차단해줘"));
        assertFalse(RequestedApp.savedContactBlock("연락처에 없는 전화번호를 차단해줘"));
        for(String request:List.of("계좌번호 차단해줘","전화번호 차단 해제해줘","카드 번호를 차단해줘","전화 알림을 차단해줘"))
            assertFalse(request,RequestedApp.isNumberBlock(request));
    }
    @Test public void findsInstalledAppWithoutConfusingRelatedNames(){
        var shopping=new RequestedApp("shopping","쿠팡 - 로켓배송");var play=new RequestedApp("video","쿠팡플레이");
        assertSame(shopping,RequestedApp.resolve("쿠팡에서 물 주문하고 싶어",List.of(shopping,play)));
        assertSame(play,RequestedApp.resolve("쿠팡플레이 열어줘",List.of(shopping,play)));
        assertNull(RequestedApp.resolve("쿠팡플레이 열어줘",List.of(shopping)));
        assertNull(RequestedApp.resolve("물을 주문하고 싶어",List.of(shopping,play)));
        assertNull(RequestedApp.resolve("쿠팡 열어줘",List.of(shopping,new RequestedApp("other","쿠팡"))));
    }
    @Test public void resolvesKoreanNamesForEnglishLabelledApps(){
        var youtube=new RequestedApp("com.google.android.youtube","YouTube");var music=new RequestedApp("com.google.android.apps.youtube.music","YouTube Music");
        var apps=RequestedApp.withAliases(List.of(youtube,music));
        RequestedApp found=RequestedApp.resolve("유튜브에서 고양이 영상 검색해줘",apps);
        assertEquals("com.google.android.youtube",found.packageName);assertEquals("유튜브",found.label);
        assertEquals("com.google.android.apps.youtube.music",RequestedApp.resolve("유튜브 뮤직 열어줘",apps).packageName);
        assertEquals("com.google.android.youtube",RequestedApp.resolve("YouTube 열어줘",apps).packageName);
        assertEquals("고양이 영상",AppTaskSearchIntent.query("유튜브에서 고양이 영상 검색해줘",found.label));
        assertNull(RequestedApp.resolve("크롬 열어줘",apps));
    }
    @Test public void matchesHomeIconLabelsWithoutPartialNames(){
        var names=java.util.Set.of("YouTube");
        assertTrue(ClickOverlayService.appIconLabel("YouTube",names));
        assertTrue(ClickOverlayService.appIconLabel("youtube, 새 알림 2개",names));
        assertFalse(ClickOverlayService.appIconLabel("YouTube Music",names));
        assertFalse(ClickOverlayService.appIconLabel("Google",names));
        assertFalse(ClickOverlayService.appIconLabel(null,names));
    }
    @Test public void keypadIntentUsesTheSystemDialerWhileExplicitAppContextWins() {
        var dialer=new RequestedApp("org.default.phone","전화",null,true);
        var phone=new RequestedApp(dialer.packageName,"전화");
        var contacts=new RequestedApp("org.contacts","연락처");
        var calculator=new RequestedApp("org.calc","계산기");
        var apps=List.of(phone,contacts,calculator);
        for(String goal:List.of("전화번호 키패드에 즐겨찾기 하고 싶어","ㅓㄴ화벊 키패드에 즐겨찾기 하고 싶다",
                "단축번호 등록해 줘","전화 앱에서 연락처 키패드 즐겨찾기","전화번호 키패드에서 연락처 즐겨찾기"))
            assertSame(goal,dialer,RequestedApp.resolve(goal,apps,dialer));
        assertSame(contacts,RequestedApp.resolve("연락처 앱에서 전화번호 즐겨찾기",apps,dialer));
        assertSame(calculator,RequestedApp.resolve("계산기 키패드에 즐겨찾기",apps,dialer));
        assertNull(RequestedApp.resolve("비밀번호 키패드 설정",apps,dialer));
        assertNull(RequestedApp.resolve("잠금 키패드에 즐겨찾기",apps,dialer));
        assertNull(RequestedApp.resolve("전화번호 키패드에서 연락처 즐겨찾기",List.of(contacts),null));
        assertNull(RequestedApp.resolve("즐겨찾기 하고 싶어",apps,dialer));
        assertNull(RequestedApp.resolve("전화번호 키패드에 즐겨찾기",List.of(contacts),null));
    }

    @Test public void favoriteIsNotASearchVerb() {
        for(String goal:List.of("전화번호 키패드에 즐겨찾기 하고 싶어","전화 키패드에 즐겨 찾기 해줘","연락처 즐겨찾기"))
            assertNull(goal,AppTaskSearchIntent.query(goal,"전화"));
        assertEquals("즐겨찾기",AppTaskSearchIntent.query("즐겨찾기 검색해줘","전화"));
    }
    @Test public void extractsOnlyExplicitSearchPhrase(){
        assertEquals("물",AppTaskSearchIntent.query("쿠팡에서 물 주문하고 싶어","쿠팡"));
        assertEquals("생수",AppTaskSearchIntent.query("쿠팡에서 생수를 찾아줘","쿠팡"));
        assertEquals("가을",AppTaskSearchIntent.query("가을 검색해줘",null));
        assertEquals("공룡",AppTaskSearchIntent.explicitInput("검색창에 공룡을 입력해 줘","검색"));
        assertEquals("가을",AppTaskSearchIntent.explicitInput("검색창에 가을 입력해 줘","검색"));
        assertNull(AppTaskSearchIntent.explicitInput("제목에 ‘치과 예약’을 입력해 줘","검색"));
        assertEquals("무선 이어폰",AppTaskSearchIntent.query("쿠팡에서 무선 이어폰 검색해줘","쿠팡"));
        for(String goal:List.of("쿠팡 열어줘","쿠팡에서 주문 내역 찾아줘","쿠팡에서 물 주문 취소해줘","쿠팡에서 결제하고 싶어","쿠팡에서 물 말고 우유 찾아줘"))assertNull(goal,AppTaskSearchIntent.query(goal,"쿠팡"));
    }
}
