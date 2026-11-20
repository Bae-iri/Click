package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class Gov24GoalCatalogTest {
    private String resource(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/"+name)) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test public void publicGuidesResolveOnTheServerWithoutInventingTypedGraphs() throws Exception {
        var catalog=GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        var server=AgentServerContract.fromOpenApi(new JSONObject(resource("openapi.json")));
        assertEquals("2.6.0",catalog.version());
        for(String request:new String[]{"정부24 로그인 도와줘","정부24 주민등록등본 발급 화면 찾아줘",
                "정부24 주민등록초본 발급 화면 찾아줘","정부24 건축물대장 열람 화면 보여줘",
                "정부24 토지대장 발급 화면 찾아줘","정부24 전입신고 화면 찾아줘","정부24 자동차등록원부 발급 화면 찾아줘"}) {
            var match=catalog.classify(request,"kr.go.minwon.m").match;
            assertNotNull(match);assertEquals("public_guide",match.executionStatus);
            assertFalse(match.isExecutable());assertNull(catalog.executableGoal(match.goalType));
            assertTrue(LocalGoalResolver.needsServerResolution(catalog,server,request,"kr.go.minwon.m",null));
        }
        for(String request:new String[]{"정부24 전입신고 화면 찾아줘","정부24 주민등록초본 발급 화면 찾아줘",
                "정부24 건축물대장 열람 화면 보여줘"}) {
            assertTrue(LocalGoalResolver.needsServerResolution(catalog,server,request,"kr.go.minwon.m",null));
        }
        server.gov24Planner=false;
        assertFalse(LocalGoalResolver.needsServerResolution(catalog,server,
            "정부24 소득금액증명 화면 찾아줘","kr.go.minwon.m",null));
    }
    @Test public void onlyCoupangPageNavigationIsInTheActiveCatalog() throws Exception {
        var catalog=GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        assertNotNull(catalog.executableGoal("coupang.order.list"));
        assertNotNull(catalog.executableGoal("coupang.cart.view"));
        assertEquals("coupang.order.list",catalog.classify("쿠팡 주문목록 보여줘","com.coupang.mobile").match.goalType);
        assertNull(catalog.executableGoal("coupang.order.cancel"));
        assertNull(catalog.executableGoal("coupang.cart.add"));
    }
}
