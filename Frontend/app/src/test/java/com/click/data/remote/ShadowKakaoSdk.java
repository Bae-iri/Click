package com.click.data.remote;

import android.content.Context;
import com.kakao.sdk.common.KakaoSdk;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Application lifecycle tests must not initialize the real OAuth SDK or use local app keys. */
@Implements(value=KakaoSdk.class,isInAndroidSdk=false)
public class ShadowKakaoSdk {
    @Implementation public static void init(Context context,String appKey) {}
}
