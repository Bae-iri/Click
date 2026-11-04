package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LocalAccountStoreTest {
    @Test public void retiredDemographicsArePurgedWithoutLosingAccountDisplay() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("click_local_account", Context.MODE_PRIVATE);
        prefs.edit().putString("name", "display").putString("email", "account@example.com")
                .putString("age", "60").putString("job", "retired").putString("gender", "old")
                .putBoolean("signed_up", true).commit();
        LocalAccountStore store = new LocalAccountStore(context);
        assertFalse(prefs.contains("age"));
        assertFalse(prefs.contains("job"));
        assertFalse(prefs.contains("gender"));
        assertEquals("display", store.name());
        assertEquals("account@example.com", store.email());
        assertTrue(store.hasAccount());
    }
}
