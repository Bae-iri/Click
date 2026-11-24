package com.click.data.remote;
import android.app.Activity;
import android.app.Application;
import android.content.DialogInterface;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class KakaoRegistrationConfirmationTest {
 @Test public void cancellationNeverStartsAuthenticationOrRegistration() {
  try (var controller=Robolectric.buildActivity(Activity.class).setup()) {
   AtomicInteger proceed=new AtomicInteger(), cancel=new AtomicInteger();
   KakaoSignIn.confirmRegistration(controller.get(),proceed::incrementAndGet,cancel::incrementAndGet);
   var dialog=ShadowAlertDialog.getLatestAlertDialog();
   assertTrue(Shadows.shadowOf(dialog).getMessage().toString().contains("새 계정"));
   assertEquals(0,proceed.get());
   dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertEquals(0,proceed.get());assertEquals(1,cancel.get());
  }
 }
 @Test public void explicitContinueStartsAuthenticationExactlyOnce() {
  try (var controller=Robolectric.buildActivity(Activity.class).setup()) {
   AtomicInteger proceed=new AtomicInteger();
   KakaoSignIn.confirmRegistration(controller.get(),proceed::incrementAndGet,()->fail("cancelled"));
   ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertEquals(1,proceed.get());
  }
 }
}
