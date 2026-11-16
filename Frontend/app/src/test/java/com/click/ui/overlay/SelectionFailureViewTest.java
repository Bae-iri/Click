package com.click.ui.overlay;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class SelectionFailureViewTest {
    private Button find(ViewGroup group, String label) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && ((Button) child).getText().toString().equals(label)) return (Button) child;
            if (child instanceof ViewGroup) {
                Button found = find((ViewGroup) child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void explainsConnectionRepairBeforeOfferingAnotherAttempt() {
        TaskFlowOverlayView view = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),
                mock(TaskFlowOverlayView.Listener.class));
        String message = "네트워크에 연결하지 못했어요. 인터넷 연결을 복구한 뒤 다시 확인해 주세요.";
        int[] calls = {0};
        view.selectionRecovery(message, "connection_restored", () -> calls[0]++);
        assertEquals(message, view.guidanceMessage());
        assertNull(find(view, "다시 시도하기"));
        assertEquals(0, calls[0]);
        find(view, "연결 복구 후 확인").performClick();
        assertEquals(1, calls[0]);
    }

    @Test public void retainsTheQuestionWhenSelectionNeedsUserClarification() {
        TaskFlowOverlayView view = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),
                mock(TaskFlowOverlayView.Listener.class));
        String message = "어느 메뉴를 원하시나요? 원하는 메뉴를 직접 열어 주세요.";
        view.selectionRecovery(message, "user_clarification", () -> {});
        assertEquals(message, view.guidanceMessage());
        assertNotNull(find(view, "화면 변경 후 확인"));
    }
}
