package com.click.testing;

/** Runs the production lifecycle callbacks without starting a real OAuth SDK. */
public final class ClickTestApplication extends com.click.ClickApplication {
    @Override protected void initializeSignIn() { }
}
