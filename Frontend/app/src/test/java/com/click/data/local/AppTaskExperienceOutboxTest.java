package com.click.data.local;

import android.app.Application;
import android.content.*;
import com.click.service.overlay.AppTaskMemory;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AppTaskExperienceOutboxTest {
    private final SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),Context.MODE_PRIVATE);
    private JSONObject event(String task,int seq)throws Exception{return new JSONObject().put("event_id",UUID.randomUUID().toString()).put("task_id",task).put("sequence",seq).put("observed_at_ms",System.currentTimeMillis());}
    @Test public void sequenceAndEventSurviveNewStoreAndOnlyMatchingAcknowledgmentRemovesThem()throws Exception {
        AppTaskStore a=new AppTaskStore(prefs,"u",()->"u");AppTaskMemory memory=new AppTaskMemory("검색 열기");memory.experienceSequence(1);JSONObject event=event(memory.id(),1);a.saveExperience(memory,"host","epoch",event);
        AppTaskStore b=new AppTaskStore(prefs,"u",()->"u");assertEquals(1,b.restore(memory.goal()).experienceSequence());assertEquals(event.getString("event_id"),b.experienceBatch().getJSONArray("events").getJSONObject(0).getString("event_id"));
        b.acknowledgeExperiences(new JSONArray().put("other-id"));assertNotNull(a.experienceBatch());b.acknowledgeExperiences(new JSONArray().put(event.getString("event_id")));assertNull(a.experienceBatch());
    }
    @Test public void ownerChangeAndLocalOptOutCannotUploadOrAppendEvidence()throws Exception {
        AtomicReference<String> user=new AtomicReference<>("u");AppTaskStore a=new AppTaskStore(prefs,"u",user::get);AppTaskMemory m=new AppTaskMemory("검색 열기");a.saveExperience(m,"host","epoch",event(m.id(),1));user.set("other");assertNull(a.experienceBatch());
        user.set("u");prefs.edit().putBoolean(AppTaskStore.consentKey("u"),false).commit();a.retainExperienceConsent(null);a.saveExperience(m,"host","epoch",event(m.id(),2));assertNull(a.experienceBatch());prefs.edit().putBoolean(AppTaskStore.consentKey("u"),true).commit();assertNull(a.experienceBatch());
    }
    @Test public void regrantPurgesOldEpochAndBatchDoesNotMixHostOrConsent()throws Exception {
        AppTaskStore a=new AppTaskStore(prefs,"u",()->"u");AppTaskMemory m=new AppTaskMemory("검색 열기");a.saveExperience(m,"host1","old",event(m.id(),1));a.saveExperience(m,"host2","new",event(m.id(),2));a.retainExperienceConsent("new");JSONObject batch=a.experienceBatch();assertEquals("new",batch.getString("consent_id"));assertEquals("host2",batch.getString("session_id"));assertEquals(1,batch.getJSONArray("events").length());
    }
    @Test public void offlineStorageEvictsWholeOldTasksAndExpiresAfterOneDay()throws Exception {
        AppTaskStore a=new AppTaskStore(prefs,"u",()->"u");for(int t=0;t<5;t++){AppTaskMemory m=new AppTaskMemory("task"+t);for(int i=1;i<=66;i++)a.saveExperience(m,"host","epoch",event(m.id(),i));}
        JSONObject batch=a.experienceBatch();assertEquals(20,batch.getJSONArray("events").length());assertEquals(1,batch.getJSONArray("events").getJSONObject(0).getInt("sequence"));
        AppTaskMemory old=new AppTaskMemory("old");JSONObject stale=event(old.id(),1).put("observed_at_ms",System.currentTimeMillis()-86400001L);AppTaskStore fresh=new AppTaskStore(prefs,"new-user",()->"new-user");fresh.saveExperience(old,"host","epoch",stale);assertNull(fresh.experienceBatch());
    }
}
