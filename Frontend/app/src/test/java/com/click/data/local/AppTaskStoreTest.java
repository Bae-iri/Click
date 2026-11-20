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
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AppTaskStoreTest {
    private SharedPreferences prefs(){return RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),Context.MODE_PRIVATE);}
    private AppTaskMemory done()throws Exception {AppTaskMemory m=new AppTaskMemory("검색 열기");m.localPlan();JSONObject s=observation("org.example.app","검색");m.record(s,action(s,"tap","screen_changed"),"verified",true);m.confirmResult();return m;}
    @Test public void checkpointRestoresProgressButCannotCrossUsers()throws Exception {
        SharedPreferences p=prefs();AtomicReference<String> user=new AtomicReference<>("user1");AppTaskStore s=new AppTaskStore(p,"user1",user::get);AppTaskMemory m=new AppTaskMemory("검색 열기 그리고 목록 확인");m.localPlan();JSONObject screen=observation("org.example.app","검색");m.record(screen,action(screen,"tap","screen_changed"),"verified",true);s.save(m);
        assertEquals(m.id(),s.restore(m.goal()).id());assertEquals("g1",s.restore(m.goal()).active());user.set("user2");assertNotEquals(m.id(),s.restore(m.goal()).id());AppTaskStore other=new AppTaskStore(p,"user2",user::get);assertNotEquals(m.id(),other.restore(m.goal()).id());
    }
    @Test public void routeNeedsTwoDistinctSuccessfulTasksAndSameAppVersion()throws Exception {
        AppTaskStore s=new AppTaskStore(prefs(),"user",()->"user");AppTaskMemory first=done();s.recordRoute(first,"pkg|1|34");s.recordRoute(first,"pkg|1|34");assertNull(s.reusable(first.goal(),"pkg|1|34"));
        s.recordRoute(done(),"pkg|1|34");assertNotNull(s.reusable(first.goal(),"pkg|1|34"));assertNull(s.reusable(first.goal(),"pkg|2|34"));s.invalidateRoute(first.goal(),"pkg|1|34");assertNull(s.reusable(first.goal(),"pkg|1|34"));
    }
    @Test public void failedOrUnverifiedPartialRoutesAreNotLearned()throws Exception {
        AppTaskStore s=new AppTaskStore(prefs(),"user",()->"user");
        for(int i=0;i<2;i++){AppTaskMemory m=new AppTaskMemory("검색 열기");m.localPlan();JSONObject screen=observation("org.example.app","검색");m.record(screen,action(screen,"tap","screen_changed"),"verified",false);m.confirmResult();s.recordRoute(m,"pkg|1");}assertNull(s.reusable("검색 열기","pkg|1"));
    }
}
