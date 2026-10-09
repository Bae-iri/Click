package com.click.designpreview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ForegroundCountdownTest {
    @Test public void backgroundTimeDoesNotConsumeTheSplashDuration() {
        ForegroundCountdown countdown = new ForegroundCountdown(2000);
        countdown.resume(100);
        countdown.pause(850);
        assertEquals(1250, countdown.remaining(10000));
        countdown.resume(10000);
        assertEquals(1, countdown.remaining(11249));
        assertEquals(0, countdown.remaining(11250));
    }

    @Test public void savedRemainingTimeSurvivesRecreation() {
        ForegroundCountdown original = new ForegroundCountdown(2000);
        original.resume(100);
        ForegroundCountdown recreated = new ForegroundCountdown(original.remaining(700));
        recreated.resume(1100);
        assertEquals(1, recreated.remaining(2499));
        assertEquals(0, recreated.remaining(2500));
    }

    @Test public void repeatedResumeDoesNotResetTheTimer() {
        ForegroundCountdown countdown = new ForegroundCountdown(2000);
        countdown.resume(100);
        countdown.resume(500);
        assertEquals(1500, countdown.remaining(600));
    }

    @Test public void expiredOrRepeatedlyPausedTimerNeverBecomesNegative() {
        ForegroundCountdown countdown = new ForegroundCountdown(2000);
        countdown.resume(100);
        countdown.pause(5000);
        countdown.pause(6000);
        countdown.resume(7000);
        assertEquals(0, countdown.remaining(9000));
    }
}
