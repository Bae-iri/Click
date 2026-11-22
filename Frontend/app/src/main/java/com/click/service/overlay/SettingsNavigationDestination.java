package com.click.service.overlay;

import com.click.data.snapshot.ScreenIdentity;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsCapabilities;
import java.util.Set;
import org.json.JSONObject;

/** Local public-toolbar proof for Settings destinations hosted by system companions. */
final class SettingsNavigationDestination {
    private SettingsNavigationDestination() {}
    static String companionTitle(JSONObject goal,ScreenSnapshot screen) {
        if(goal==null || screen==null || screen.packageName==null || !"android_settings".equals(goal.optString("target_app"))
                || !Set.of("com.android.soundpicker","com.samsung.android.app.soundpicker",
                    "com.samsung.android.secsoundpicker").contains(screen.packageName))return null;
        String title;Set<String> headings;
        switch(goal.optString("goal_type")) {
            case "settings.navigation.ringtone":title="벨소리";headings=Set.of("벨소리","Ringtone","Phone ringtone");break;
            case "settings.navigation.notification_sound":title="알림 소리";headings=Set.of("알림 소리","Notification sound","Default notification sound");break;
            default:return null;
        }
        JSONObject slots=goal.optJSONObject("slots");
        if(slots==null || !("navigation_"+goal.optString("goal_type").substring("settings.navigation.".length())).equals(slots.optString("setting"))
                || !"open".equals(slots.optString("action")))return null;
        String observed=ScreenIdentity.pageTitle(screen);
        return observed!=null && headings.stream().map(SettingsCapabilities::canonical).anyMatch(observed::equals)?title:null;
    }
}
