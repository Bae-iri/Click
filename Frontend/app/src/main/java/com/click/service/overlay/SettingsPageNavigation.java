package com.click.service.overlay;

import com.click.data.snapshot.ScreenIdentity;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsCapabilities;
import com.click.data.snapshot.VisionPrivacy;
import java.util.Objects;

/** Native page changes are navigation, not unsuccessful guided actions.
 * Only independent headings count. Scrolling, discovery lists, values and window churn do not.
 */
final class SettingsPageNavigation {
    enum Change { NONE, ON_ROUTE, OFF_ROUTE }
    private final VisionPrivacy vocabulary;
    SettingsPageNavigation(VisionPrivacy vocabulary) { this.vocabulary = vocabulary; }
    Change changed(ScreenSnapshot before, ScreenSnapshot current, String setting) {
        if (vocabulary == null || before == null || current == null
                || !SettingsCapabilities.settingsPackage(before.packageName)
                || !Objects.equals(before.packageName,current.packageName)) return Change.NONE;
        String previous=ScreenIdentity.pageTitle(before),now=ScreenIdentity.pageTitle(current);
        if (previous==null || now==null || previous.equals(now))return Change.NONE;
        return onRoute(current,setting) ? Change.ON_ROUTE : Change.OFF_ROUTE;
    }
    boolean onRoute(ScreenSnapshot current,String setting) {
        String title=ScreenIdentity.pageTitle(current);
        if(current!=null && "SUBPAGE".equals(current.windowContext.settingsActivity())
                && com.click.data.snapshot.ScreenEnvironmentPolicy.contains("settings_home_titles",title))return false;
        if(vocabulary==null || title==null || setting==null)return false;
        // This is the existing reviewed scope, not permission to export another page's controls.
        for(var node:current.nodes)
            if(node.text!=null && title.equals(SettingsCapabilities.canonical(node.text))
                    && vocabulary.exportLabel(node.text,setting)!=null)return true;
        return false;
    }
}
