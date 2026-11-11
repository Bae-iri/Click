package com.click.data.snapshot;
import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;
public class SettingsCapabilitiesTest {
 @Test public void compiledAndroidContractMatchesTheSharedAssetByteForByte() throws Exception {
  byte[] raw=getClass().getResourceAsStream("/settings_capabilities.json").readAllBytes();
  StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(raw))hash.append(String.format("%02x",b&255));
  assertEquals(hash.toString(),SettingsCapabilities.sha256());
  JSONObject doc=new JSONObject(new String(raw,StandardCharsets.UTF_8));
  JSONArray states=doc.getJSONArray("navigation_states");for(int i=0;i<states.length();i++)assertTrue(SettingsCapabilities.navigationState(states.getString(i)));
  assertTrue(SettingsCapabilities.navigationState("S2_SOUND_MENU"));
  assertFalse(SettingsCapabilities.navigationState("S_TARGET"));assertFalse(SettingsCapabilities.navigationState("S_DONE"));
 }
 @Test public void expandedMenuNamesAreReviewedButValuesCredentialsAndDeviceNamesAreNot() {
  for(String label:List.of("연결","블루투스","Wi-Fi","배터리","알림"))assertTrue(label,SettingsCapabilities.navigationLabel(label));
  for(String label:List.of("삭제","확인","허용","Synthetic headphones"))assertFalse(label,SettingsCapabilities.navigationLabel(label));
  assertTrue(SettingsCapabilities.privateIdentity("com.android.settings:id/bluetooth_device"));
  assertEquals("블루투스",SettingsCapabilities.targetLabel("settings.bluetooth.enable","S_CATEGORY"));
 }
 @Test public void mismatchedContractCannotAuthorizeAutomaticExecution() throws Exception {
  assertTrue(SettingsCapabilities.compatible(null));
  JSONObject remote=new JSONObject().put("schema_version","settings-capabilities.v1").put("sha256",SettingsCapabilities.sha256());
  assertTrue(SettingsCapabilities.compatible(remote));remote.put("sha256","0".repeat(64));assertFalse(SettingsCapabilities.compatible(remote));
 }
}
