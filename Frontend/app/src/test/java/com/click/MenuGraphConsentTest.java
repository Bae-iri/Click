package com.click;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.data.remote.GraphImprovementConsentGateway;
import com.click.data.remote.AccountActionsGateway;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MenuGraphConsentTest {
    @Test public void menuOffersSharedRouteParticipationAndRemovesPersonalizedGuidance() {
        try (MockedConstruction<GraphImprovementConsentGateway> gateway = mockConstruction(GraphImprovementConsentGateway.class);
             MockedConstruction<AccountActionsGateway> account = mockConstruction(AccountActionsGateway.class)) {
            ActivityController<MenuActivity> controller = Robolectric.buildActivity(MenuActivity.class).setup();
            try {
                View root = controller.get().getWindow().getDecorView();
                assertTrue(contains(root, "안내 경로 개선 참여"));
                assertFalse(contains(root, "나에게 맞는 안내"));
                assertFalse(contains(root, "맞춤 안내 기록"));
                verify(gateway.constructed().get(0)).read(any());
            } finally { controller.pause().stop().destroy(); }
            verify(gateway.constructed().get(0)).close();
        }
    }

    private boolean contains(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) if (contains(parent.getChildAt(i), label)) return true;
        }
        return false;
    }
}
