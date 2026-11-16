package com.click.service.accessibility;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
public class NativeWindowContextReaderTest {
 @Test public void settingsActivityEvidenceMustBelongToTheActualPackageAndWindow() {
  var service=mock(AccessibilityService.class);var root=mock(AccessibilityNodeInfo.class);
  when(root.getPackageName()).thenReturn("com.android.settings");when(root.getWindowId()).thenReturn(7);when(service.getWindows()).thenReturn(List.of());
  var matching=NativeWindowContextReader.read(service,root,"com.android.settings",7,"com.android.settings.Settings$VpnSettingsActivity");
  assertEquals("SUBPAGE",matching.settingsActivity());
  assertEquals("UNKNOWN",NativeWindowContextReader.read(service,root,"com.android.settings",8,"com.android.settings.Settings$VpnSettingsActivity").settingsActivity());
  assertEquals("UNKNOWN",NativeWindowContextReader.read(service,root,"com.samsung.android.calendar",7,"com.android.settings.Settings$VpnSettingsActivity").settingsActivity());
 }
 @Test public void focusedKeyboardIsAnInterruptionOverTheUnderlyingApplication() {
  var service=mock(AccessibilityService.class);var root=mock(AccessibilityNodeInfo.class);var ime=mock(AccessibilityWindowInfo.class);
  when(root.getPackageName()).thenReturn("com.android.settings");when(root.getWindowId()).thenReturn(7);
  when(ime.getType()).thenReturn(AccessibilityWindowInfo.TYPE_INPUT_METHOD);when(ime.isFocused()).thenReturn(true);when(service.getWindows()).thenReturn(List.of(ime));
  assertTrue(NativeWindowContextReader.read(service,root,null,null,null).keyboardVisible());verify(ime).recycle();
 }
}
