package com.click.service.overlay;

import org.json.*;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Unambiguous, explicitly named controls need no model or app-specific route. */
public final class AppGuideLocalMatch {
    private static final Pattern BOUNDARY=Pattern.compile("결제|송금|이체|구매|주문확정|제출|탈퇴|삭제|로그인|인증|비밀번호|보내기|동의|허용|승인|payment|checkout|purchase|transfer|delete|submit|send|login|sign.?in|accept|allow",Pattern.CASE_INSENSITIVE);
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    public static JSONObject select(String goal,JSONObject screen)throws JSONException {
        long started=System.nanoTime();
        if(!"public".equals(screen.optString("status")))return null;
        String request=normalize(goal);
        if(request.matches(".*(?:말고|아니|하지|지마|취소|않).*"))return null;
        JSONArray nodes=screen.optJSONArray("nodes");JSONObject match=null;
        for(int i=0;nodes!=null&&i<nodes.length();i++) {
            JSONObject node=nodes.getJSONObject(i);String label=node.optString("label"),key=normalize(label);
            if(!node.optBoolean("clickable")||key.length()<2||!request.contains(key)
                    ||BOUNDARY.matcher(label+" "+node.optString("resource_id")).find())continue;
            if(match!=null)return null;
            match=node;
        }
        if(match==null)return null;
        return new JSONObject().put("status","guide").put("message","‘"+match.getString("label")+"’ 항목을 눌러주세요.")
            .put("manual_only",true).put("observation_id",screen.getString("observation_id"))
            .put("target_node_id",match.getString("node_id")).put("target_bounds",match.getJSONArray("bounds"))
            .put("action_kind","tap").put("diagnostic",new JSONObject().put("model","on-device-label-match")
                .put("called",false).put("reason","unique_explicit_control").put("latency_ms",(System.nanoTime()-started)/1000000L));
    }
}
