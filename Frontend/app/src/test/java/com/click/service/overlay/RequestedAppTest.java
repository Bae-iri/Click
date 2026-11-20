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
    @Test public void extractsOnlyExplicitSearchPhrase(){
        assertEquals("물",AppTaskSearchIntent.query("쿠팡에서 물 주문하고 싶어","쿠팡"));
        assertEquals("생수",AppTaskSearchIntent.query("쿠팡에서 생수를 찾아줘","쿠팡"));
        assertEquals("무선 이어폰",AppTaskSearchIntent.query("쿠팡에서 무선 이어폰 검색해줘","쿠팡"));
        for(String goal:List.of("쿠팡 열어줘","쿠팡에서 주문 내역 찾아줘","쿠팡에서 물 주문 취소해줘","쿠팡에서 결제하고 싶어","쿠팡에서 물 말고 우유 찾아줘"))assertNull(goal,AppTaskSearchIntent.query(goal,"쿠팡"));
    }
}
