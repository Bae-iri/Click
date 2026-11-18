package com.click.service.accessibility;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.click.data.snapshot.Gov24LoginPrivacy;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public semantic targets only; never returns editable text or an unfiltered tree. */
public final class Gov24PublicTestTarget {
    private Gov24PublicTestTarget() {}
    public static JSONObject inspect(UiAutomation automation, Context context, String ownPackage) throws Exception {
        AccessibilityNodeInfo foreground = null;
        int layer = Integer.MIN_VALUE;
        JSONArray overlays = new JSONArray();
        try {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    if (root == null) continue;
                    String pkg = String.valueOf(root.getPackageName());
                    if (ownPackage.equals(pkg)) {
                        Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                        overlays.put(new JSONObject().put("bounds", new JSONArray(new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom}))
                                .put("root_class", String.valueOf(root.getClassName())).put("root_id", root.getViewIdResourceName()));
                    }
                    if (Gov24LoginPrivacy.PACKAGE.equals(pkg) && window.getLayer() > layer) {
                        if (foreground != null) foreground.recycle();
                        foreground = AccessibilityNodeInfo.obtain(root); layer = window.getLayer();
                    }
                } finally { if (root != null) root.recycle(); window.recycle(); }
            }
            if (foreground == null) throw new AssertionError("Government24 is not in an observed native window");
            var screen = new AccessibilitySnapshotCollector().collect(foreground, Gov24LoginPrivacy.PACKAGE, context, null);
            java.util.Set<String> providers = new java.util.HashSet<>();
            int inputs=0, selected=0;
            for(var node:screen.nodes)if(node.visibleToUser) {
                if(node.sensitiveInput){inputs++;continue;}
                String option=Gov24LoginPrivacy.option(node.text!=null?node.text:node.contentDescription);
                if(option!=null&&option.startsWith("provider_")) {
                    providers.add(option);if(node.selected||Boolean.TRUE.equals(node.checked))selected++;
                }
            }
            JSONObject result = new Gov24LoginPrivacy().filter(screen);
            result.put("provider_choice_count",providers.size()).put("visible_input_count",inputs)
                    .put("selected_provider_count",selected);
            result.put("local_diagnostics",new JSONObject().put("provider_choices",providers.size())
                .put("visible_input_fields",inputs).put("selected_provider_markers",selected));
            result.put("click_windows", overlays);
            return result;
        } finally { if (foreground != null) foreground.recycle(); }
    }
}
