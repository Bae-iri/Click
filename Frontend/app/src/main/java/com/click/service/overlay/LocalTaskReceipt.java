package com.click.service.overlay;

import org.json.*;

/** Local observations are not server-verified completion or user satisfaction. */
final class LocalTaskReceipt {
    static String message(JSONObject plan,JSONObject wire,Integer wifiState,Integer airplaneMode) {
        String setting=plan==null?"":plan.optString("setting");
        String observation="기기의 최종 변경 결과는 아직 확인하지 못했어요.";
        if("wifi".equals(setting) && wifiState!=null && (wifiState==1 || wifiState==3))
            observation="현재 와이파이 전원이 "+(wifiState==3?"켜져":"꺼져")+" 있는 것을 기기에서 확인했어요. 네트워크 연결 여부와는 별개예요.";
        else if("airplane_mode".equals(setting) && airplaneMode!=null && (airplaneMode==0 || airplaneMode==1))
            observation="현재 비행기 모드가 "+(airplaneMode==1?"켜져":"꺼져")+" 있는 것을 기기에서 확인했어요.";
        else if(wire!=null) {
            JSONObject tree=wire.optJSONObject("accessibility_tree"),event=wire.optJSONObject("user_action_event");
            JSONArray nodes=tree==null?null:tree.optJSONArray("nodes");
            if(nodes!=null && event!=null)for(int i=0;i<nodes.length();i++) {
                JSONObject node=nodes.optJSONObject(i),extra=node==null?null:node.optJSONObject("extras");
                if(node!=null && event.optString("target_node_id").equals(node.optString("node_id")) && node.optBoolean("checkable")
                        && extra!=null && extra.opt("checked") instanceof Boolean) {
                    observation="화면의 스위치는 "+(extra.optBoolean("checked")?"켜짐":"꺼짐")+"으로 관찰됐어요. 실제 기능 적용 여부는 아직 확인하지 못했어요.";break;
                }
            }
        }
        return observation+" 진행 기록은 이 기기에 보관했어요. 연결 후 서버 기록을 확인할 수 있어요. 같은 조작을 다시 실행하지 않을게요.";
    }
}
