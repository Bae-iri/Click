package com.click.data.remote;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class InteractionGatewayResponseTest {
    @Test public void absentAndJsonNullDetailsRemainEmptyOnAndroid() throws Exception {
        for (String detail : new String[]{"", ",\"detail\":null", ",\"detail\":\"null\""}) {
            var response = new InteractionGateway.Response("{\"intent\":\"negative_feedback\",\"message\":\"알려주세요\","
                    + "\"feedback\":{\"id\":\"feedback-1\"" + detail + "}}");
            assertNull(response.detail);
        }
    }
    @Test public void realDraftIsPreservedAndNullFeedbackIdentityIsRejected() throws Exception {
        var response = new InteractionGateway.Response("{\"intent\":\"negative_feedback\",\"message\":\"알려주세요\","
                + "\"feedback\":{\"id\":\"feedback-1\",\"detail\":\"스크롤이 안 돼요\"}}");
        assertEquals("스크롤이 안 돼요",response.detail);
        assertThrows(IllegalArgumentException.class, () -> new InteractionGateway.Response(
                "{\"intent\":\"negative_feedback\",\"message\":\"알려주세요\",\"feedback\":{\"id\":null}}"));
    }
}
