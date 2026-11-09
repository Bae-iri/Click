package com.click.data.remote;

import android.app.UiModeManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in read-only check. Never sets the system mode or opens a personal screen. */
@RunWith(AndroidJUnit4.class)
public class NativeModeValueTest {
    @Test public void readTheCurrentSystemModeWithoutChangingIt() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("readSystemMode")));
        var app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiModeManager manager=app.getSystemService(UiModeManager.class);assertNotNull(manager);
        int mode=manager.getNightMode();
        JSONObject result=new JSONObject().put("source","android.app.UiModeManager.getNightMode")
            .put("value",mode).put("actions",0).put("explicit_mode",mode==1 || mode==2);
        Files.write(new File(app.getCacheDir(),"system-mode-observation.json").toPath(),result.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(mode>=0 && mode<=3);
    }
}
