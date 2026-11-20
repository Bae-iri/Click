package com.click.service.overlay;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestedAppTest {
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
    @Test public void extractsOnlyExplicitSearchPhrase(){
        assertEquals("물",AppTaskSearchIntent.query("쿠팡에서 물 주문하고 싶어","쿠팡"));
        assertEquals("생수",AppTaskSearchIntent.query("쿠팡에서 생수를 찾아줘","쿠팡"));
        assertEquals("무선 이어폰",AppTaskSearchIntent.query("쿠팡에서 무선 이어폰 검색해줘","쿠팡"));
        for(String goal:List.of("쿠팡 열어줘","쿠팡에서 주문 내역 찾아줘","쿠팡에서 물 주문 취소해줘","쿠팡에서 결제하고 싶어","쿠팡에서 물 말고 우유 찾아줘"))assertNull(goal,AppTaskSearchIntent.query(goal,"쿠팡"));
    }
}
