package com.click.service.accessibility;

import com.click.data.snapshot.*;
import com.click.service.overlay.AppTaskPolicy;
import org.json.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskVisionTest {
    @Test public void ocrOnlyLabelsExistingUnnamedNativeButtonsAndKeepsBounds()throws Exception {
        JSONObject before=observation("org.example.app","");
        JSONObject after=AppTaskVision.enrich(before,List.of(new VisionOcrElement("ocr1","검색",new int[]{20,30,200,80},"text",.95f,false),new VisionOcrElement("ocr2","PRIVATE_OUTSIDE",new int[]{500,600,700,800},"text",.95f,false)));
        assertEquals(1,after.getJSONArray("nodes").length());JSONObject n=after.getJSONArray("nodes").getJSONObject(0);assertEquals("검색",n.getString("label"));assertEquals("ocr",n.getString("source"));assertEquals(before.getJSONArray("nodes").getJSONObject(0).getJSONArray("bounds").toString(),n.getJSONArray("bounds").toString());assertFalse(after.toString().contains("PRIVATE_OUTSIDE"));
        assertFalse(AppTaskPolicy.allowed(after,after,action(after,"tap","screen_changed"),null));assertTrue(AppTaskPolicy.manualTapAllowed(after,action(after,"tap","screen_changed")));
    }
    @Test public void piiLowConfidenceAndNamedControlsAreNotReplaced()throws Exception {
        JSONObject before=observation("org.example.app","");
        JSONObject after=AppTaskVision.enrich(before,List.of(new VisionOcrElement("ocr1","010-1234-5678",new int[]{20,30,200,80},"text",.95f,false),new VisionOcrElement("ocr2","불확실",new int[]{20,30,200,80},"text",.2f,false)));
        assertEquals("",after.getJSONArray("nodes").getJSONObject(0).getString("label"));assertFalse(after.toString().contains("010-1234"));
        before=observation("org.example.app","검색");after=AppTaskVision.enrich(before,List.of(new VisionOcrElement("ocr3","다른 이름",new int[]{20,30,200,80},"text",.95f,false)));assertEquals("검색",after.getJSONArray("nodes").getJSONObject(0).getString("label"));
    }
}
