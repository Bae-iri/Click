package com.click.testing;

import com.click.data.snapshot.*;
import org.json.*;
import java.time.Instant;
import java.util.List;

public final class AppTaskFixtures {
    public static AccessibilityNodeSnapshot node(String pkg,String id,String text,String kind){return node(pkg,id,text,kind,false);}
    public static AccessibilityNodeSnapshot node(String pkg,String id,String text,String kind,boolean selected){
        boolean input=kind.equals("search")||kind.equals("password"),toggle=kind.equals("toggle");
        return new AccessibilityNodeSnapshot(id,null,true,pkg,text,null,pkg+":id/"+(input?kind:id),
            input?"android.widget.EditText":kind.equals("tab")?"android.widget.TabWidget":kind.equals("list")?"androidx.recyclerview.widget.RecyclerView":"android.widget.Button",
            null,new int[]{10,20,400,120},!kind.equals("list"),true,selected,kind.equals("list"),toggle,null,42,input,null,null,kind.equals("password"),toggle?selected:null,false,input);
    }
    public static ScreenSnapshot screen(String pkg,AccessibilityNodeSnapshot...nodes){return new ScreenSnapshot(pkg,Instant.ofEpochMilli(System.currentTimeMillis()).toString(),1080,2400,List.of(nodes),42);}
    public static JSONObject observation(String pkg,String label)throws Exception{return AppTaskObservation.capture(screen(pkg,node(pkg,"node_0_1",label,"button")));}
    public static JSONObject action(JSONObject screen,String tool,String kind)throws Exception {
        JSONObject n=screen.getJSONArray("nodes").length()==0?null:screen.getJSONArray("nodes").getJSONObject(0);
        return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("tool",tool).put("target_id",n==null?JSONObject.NULL:n.getString("id"))
            .put("bounds",n==null?JSONObject.NULL:n.getJSONArray("bounds")).put("package",screen.getString("package"))
            .put("expectation",new JSONObject().put("kind",kind)).put("advances_subgoal",true);
    }
}
