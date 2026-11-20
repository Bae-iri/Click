package com.click.service.overlay;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Reads OS result evidence only. Never changes a ringtone, account or queued request. */
@RunWith(AndroidJUnit4.class)
public class RingtoneResultDeviceTest {
    @Test public void persistedRingtoneCanBeReadAndAnUnchangedValueNeverCompletes(){
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        String baseline=RingtoneResultCheck.read(context);assertNotNull("Persisted ringtone evidence must be readable",baseline);
        var check=new RingtoneResultCheck();check.bind(baseline);
        assertFalse(check.changed(RingtoneResultCheck.read(context),SystemClock.uptimeMillis()));
        Bundle report=new Bundle();report.putString("stream","native_ringtone_evidence_available=true unchanged_result_requested=false queue_writes=0\n");
        instrumentation.sendStatus(0,report);
    }
}
