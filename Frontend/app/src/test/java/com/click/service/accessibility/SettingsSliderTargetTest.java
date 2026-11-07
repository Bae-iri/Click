package com.click.service.accessibility;

import com.click.data.snapshot.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsSliderTargetTest {
    private AccessibilityNodeSnapshot node(String id,String parent,String text,String resource,String role,int top) {
        return new AccessibilityNodeSnapshot(id,parent,true,"com.android.settings",text,null,resource,
            role.equals("slider")?"android.widget.SeekBar":"android.widget.TextView",role,
            new int[]{20,top,900,top+60},false,true,false,false,false,role.equals("slider")?3f:null,7,false,0f,7f);
    }
    private ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot("com.android.settings","2026-11-07T00:00:00Z",1080,2400,Arrays.asList(nodes),7,true,1.1f);
    }
    @Test public void everyTaskRejectsOtherNamedSlidersAndGenericVolumeIsInsufficient() {
        for(String setting:List.of("font_size","brightness","volume")) {
            String name=Map.of("font_size","글자 크기","brightness","밝기","volume","미디어").get(setting);
            AccessibilityNodeSnapshot selected=node("slider",null,name,null,"slider",300);
            ScreenSnapshot screen=screen(selected);
            assertTrue(SettingsSliderTarget.matches(screen,selected,setting));
            for(String other:List.of("font_size","brightness","volume"))
                if(!other.equals(setting)) assertFalse(SettingsSliderTarget.matches(screen,selected,other));
        }
        AccessibilityNodeSnapshot unnamed=node("slider",null,null,null,"slider",300);
        assertFalse(SettingsSliderTarget.matches(screen(node("title",null,"음량",null,"text",50),unnamed),unnamed,"volume"));
    }
    @Test public void siblingMediaLabelIdentifiesOnlyTheOneSliderInItsNativeRow() {
        AccessibilityNodeSnapshot row=new AccessibilityNodeSnapshot("row","root",true,"com.android.settings",null,null,null,
                "android.widget.LinearLayout","container",new int[]{0,200,1080,450},false,true,false,false,false,null,7);
        AccessibilityNodeSnapshot media=node("media","row",null,null,"slider",350);
        AccessibilityNodeSnapshot ring=node("ring",null,"벨소리",null,"slider",600);
        ScreenSnapshot screen=screen(row,node("label","row","미디어",null,"text",220),media,ring);
        assertTrue(SettingsSliderTarget.matches(screen,media,"volume"));
        assertFalse(SettingsSliderTarget.matches(screen,ring,"volume"));
        AccessibilityNodeSnapshot second=node("second","row",null,null,"slider",400);
        assertFalse(SettingsSliderTarget.matches(screen(row,node("label","row","미디어",null,"text",220),media,second),media,"volume"));
    }
}
