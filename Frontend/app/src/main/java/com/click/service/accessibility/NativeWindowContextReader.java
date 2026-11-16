package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.click.data.snapshot.*;

/** Capture local OS evidence without serializing an arbitrary app/activity name. */
final class NativeWindowContextReader {
    private NativeWindowContextReader() {}
    static NativeWindowContext read(AccessibilityService service,AccessibilityNodeInfo root,
                                    String eventPackage,Integer eventWindow,String eventClass) {
        String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
        boolean eventMatches=pkg.equals(eventPackage) && eventWindow!=null && eventWindow==root.getWindowId();
        String activity=eventMatches?ScreenEnvironmentPolicy.activity(pkg,eventClass):"UNKNOWN";
        String kind=eventMatches && ScreenEnvironmentPolicy.contains("dialog_classes",eventClass)?"DIALOG":"UNKNOWN";
        var nativeWindow=root.getWindow();
        if(nativeWindow!=null)try{
            if(!"DIALOG".equals(kind))kind=switch(nativeWindow.getType()){
                case AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION";
                case AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD";
                case AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM";
                case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "OVERLAY";
                default -> "UNKNOWN";
            };
        }finally{nativeWindow.recycle();}
        boolean keyboard=false;
        for(var window:service.getWindows())try{
            keyboard|=window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD && (window.isActive() || window.isFocused());
        }finally{window.recycle();}
        return new NativeWindowContext(activity,kind,keyboard);
    }
}
