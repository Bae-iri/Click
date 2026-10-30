package com.click.service.accessibility;
import com.click.data.local.TouchAutomationSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
public class TouchAutomationSettingsTest {
 @Test public void defaultsOffAndPersistsBothChoices() {
  android.content.Context context=RuntimeEnvironment.getApplication();
  context.getSharedPreferences("touch_automation",0).edit().clear().commit();
  TouchAutomationSettings settings=new TouchAutomationSettings(context);
  assertFalse(settings.isEnabled());
  settings.setEnabled(true);
  assertTrue(new TouchAutomationSettings(context).isEnabled());
  settings.setEnabled(false);
  assertFalse(new TouchAutomationSettings(context).isEnabled());
 }
}
