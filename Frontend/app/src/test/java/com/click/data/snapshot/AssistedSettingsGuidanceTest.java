package com.click.data.snapshot;
import org.junit.Test;
import static org.junit.Assert.*;
public class AssistedSettingsGuidanceTest {
 private AssistedSettingsGuidance guidance() throws Exception {
  try(var input=getClass().getResourceAsStream("/manual_guidance.json")){assertNotNull(input);return AssistedSettingsGuidance.load(input);}
 }
 @Test public void vpnPurposeAndNextStepsRemainVisibleAtProtectedInput() throws Exception {
  String message=guidance().privacyMessage("settings.vpn.connect","입력 내용 보호를 위해 전송을 멈췄어요.");
  assertTrue(message.contains("VPN 연결"));assertTrue(message.contains("VPN 설정 화면으로 안내"));
  assertTrue(message.contains("프로필"));assertTrue(message.contains("비밀번호"));
  assertTrue(message.contains("전송을 멈췄어요"));assertFalse(message.contains("연결 해제"));
 }
 @Test public void disconnectAndWifiSharingDoNotReuseConnectionInstructions() throws Exception {
  String off=guidance().privacyMessage("settings.vpn.disconnect","보호 중");
  assertTrue(off.contains("연결 해제"));assertFalse(off.contains("비밀번호"));
  String share=guidance().privacyMessage("settings.wifi.share","보호 중");
  assertTrue(share.contains("공유"));assertTrue(share.contains("QR 코드"));
  assertFalse(share.contains("비밀번호가 필요하면"));
 }
 @Test public void unknownAndAutomaticGoalsKeepOriginalMessage() throws Exception {
  for(String goal:new String[]{null,"arbitrary.goal","settings.wifi.enable","settings.font_size.increase"})
   assertEquals("base",guidance().privacyMessage(goal,"base"));
 }
}
