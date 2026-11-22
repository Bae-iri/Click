package com.click.service.accessibility;

import com.click.data.snapshot.ScreenIdentity;
import com.click.data.snapshot.ScreenSnapshot;

/** UI change evidence for a dispatched navigation tap; never proof of goal completion. */
public final class NavigationTapEvidence {
    private NavigationTapEvidence() {}
    public static boolean nativeContentChanged(ScreenSnapshot before,ScreenSnapshot after) {
        return before!=null && after!=null && before.privacyMaskingReady && after.privacyMaskingReady
            && before.activeWindowId!=null && after.activeWindowId!=null
            && !before.nodes.isEmpty() && !after.nodes.isEmpty()
            && !ScreenIdentity.navigationFingerprint(before).equals(ScreenIdentity.navigationFingerprint(after));
    }
}
