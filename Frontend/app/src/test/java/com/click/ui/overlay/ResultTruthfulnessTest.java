package com.click.ui.overlay;

import android.view.*;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class ResultTruthfulnessTest {
    private boolean contains(View v,String text) {
        if(v instanceof TextView && ((TextView)v).getText().toString().contains(text))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),text))return true;
        return false;
    }
    @Test public void unverifiedResultStaysVisibleAndAudibleAndDoesNotClaimSuccess() throws Exception {
        TaskFlowOverlayView panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        JSONObject prompt=new JSONObject().put("technical_status","UNVERIFIABLE")
            .put("message","변경 결과를 확인하지 못했어요. 이제 글자가 보기 편한가요?");
        panel.result(prompt,true);
        assertTrue(contains(panel,"변경 결과를 확인하지 못했어요"));
        assertTrue(contains(panel,"현재 설정이 원하시는 상태인가요"));
        assertTrue(TaskFlowOverlayView.resultNarration(prompt,true).contains("확인하지 못했어요"));
        panel.completed("사용자가 원하는 상태라고 알려주셨어요.",false,()->{});
        assertTrue(contains(panel,"요청을 마쳤어요"));
        assertTrue(contains(panel,"사용자가 원하는 상태"));
        assertFalse(contains(panel,"변경을 확인했어요"));
    }
}
