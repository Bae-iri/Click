package com.click.data.snapshot;
import java.util.*;
import org.json.*;
/** A finite native control surface, never a blanket exception for the System UI. */
public final class NativeControlSurface {
    public record Match(String setting,Set<String> nodeIds) {}
    private NativeControlSurface() {}
    public static Match identify(ScreenSnapshot screen){try{return screen==null?null:identify(new JSONObject(screen.toJson()));}catch(JSONException e){return null;}}
    private static boolean number(Object value){return value instanceof Number && Double.isFinite(((Number)value).doubleValue());}
    public static Match identify(JSONObject tree){
        JSONArray nodes=tree.optJSONArray("nodes");if(nodes==null)return null;
        JSONObject definitions=ScreenEnvironmentPolicy.controlSurfaces();
        for(String setting:List.of("brightness")) {
            JSONObject spec=definitions.optJSONObject(setting);if(spec==null || !spec.optString("package").equals(tree.optString("package_name")))continue;
            Map<String,JSONObject> byId=new HashMap<>();boolean wrapper=false;JSONObject target=null;int sliders=0;
            for(int i=0;i<nodes.length();i++) {
                JSONObject node=nodes.optJSONObject(i);if(node==null || !node.optBoolean("visible_to_user",true) || !node.optBoolean("enabled",true))continue;
                if(!spec.optString("package").equals(node.optString("package_name")))return null;
                byId.put(node.optString("node_id"),node);
                wrapper|=contains(spec.optJSONArray("wrapper_ids"),node.optString("resource_id"));
                if("slider".equals(node.optString("role")) || "android.widget.SeekBar".equals(node.optString("class_name"))) {
                    sliders++;
                    if(contains(spec.optJSONArray("slider_ids"),node.optString("resource_id")))target=node;
                }
            }
            if(!wrapper || sliders!=1 || target==null)continue;
            JSONObject range=target.optJSONObject("extras");if(range==null)continue;
            Object progress=range.opt("progress"),min=range.opt("min"),max=range.opt("max");
            if(!number(progress)||!number(min)||!number(max))continue;
            double a=((Number)min).doubleValue(),b=((Number)max).doubleValue(),v=((Number)progress).doubleValue();
            if(!(a<b && a<=v && v<=b))continue;
            Set<String> needed=new HashSet<>();String id=target.optString("node_id");
            while(id!=null && !id.isEmpty() && needed.add(id)){JSONObject node=byId.get(id);id=node==null?null:node.optString("parent_id",null);}
            boolean anchored=false;
            for(String ancestor:needed){JSONObject node=byId.get(ancestor);if(node!=null && contains(spec.optJSONArray("wrapper_ids"),node.optString("resource_id")))anchored=true;}
            if(!anchored)continue;
            return new Match(setting,Collections.unmodifiableSet(needed));
        }
        return null;
    }
    private static boolean contains(JSONArray values,String value){if(values==null)return false;for(int i=0;i<values.length();i++)if(value.equals(values.optString(i)))return true;return false;}
}
