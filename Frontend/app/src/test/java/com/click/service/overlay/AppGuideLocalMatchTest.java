package com.click.service.overlay;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppGuideLocalMatchTest {
    private JSONObject screen(String... labels)throws Exception {
        JSONArray nodes=new JSONArray();
        for(int i=0;i<labels.length;i++)nodes.put(new JSONObject().put("node_id","n"+i).put("resource_id","com.example.any:id/control"+i)
            .put("label",labels[i]).put("clickable",true).put("bounds",new JSONArray(new int[]{10,20+i*100,400,120+i*100})));
        return new JSONObject().put("status","public").put("observation_id","current").put("nodes",nodes);
    }
    @Test public void explicitlyNamedUniqueControlNeedsNoModel()throws Exception {
        JSONObject result=AppGuideLocalMatch.select("검색 열어줘",screen("검색","라이브러리"));
        assertEquals("n0",result.getString("target_node_id"));
        assertEquals("current",result.getString("observation_id"));
        assertFalse(result.getJSONObject("diagnostic").getBoolean("called"));
    }
    @Test public void ambiguousOrAbsentControlsDoNotProduceAGuess()throws Exception {
        assertNull(AppGuideLocalMatch.select("검색 열어줘",screen("검색","검색")));
        assertNull(AppGuideLocalMatch.select("검색과 메뉴 열어줘",screen("검색","메뉴")));
        assertNull(AppGuideLocalMatch.select("추천 노래 찾아줘",screen("검색","메뉴")));
    }
    @Test public void negationAndFinalActionsNeverBecomeLocalTapSuggestions()throws Exception {
        assertNull(AppGuideLocalMatch.select("검색하지 마",screen("검색")));
        assertNull(AppGuideLocalMatch.select("검색 말고 다른 걸",screen("검색")));
        for(String label:new String[]{"결제","보내기","삭제","로그인","허용","동의"})
            assertNull(AppGuideLocalMatch.select(label+" 눌러줘",screen(label)));
    }
    @Test public void nonPublicScreenCannotUseTheFastPath()throws Exception {
        assertNull(AppGuideLocalMatch.select("검색 열어줘",screen("검색").put("status","sensitive")));
    }
}
