package com.click.service.overlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.telecom.TelecomManager;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A local app destination, resolved from installed launcher entries or the system dialer role. */
public final class RequestedApp {
    public final String packageName, label;
    final String activity;
    final boolean dialpad;
    final Intent entryIntent;
    enum BlockRoute { NONE, MANAGEMENT, CONTACTS, PHONE_SETTINGS }
    final BlockRoute blockRoute;
    private static final Pattern APP_CONTEXT = Pattern.compile("^(?:(?:앱|어플)(?=$|\\s|에|으|을|이)|에서)");
    private static final Pattern NEGATED_APP = Pattern.compile("^(?:(?:앱|어플)\\s*)?(?:말고|대신)");
    private static final Set<String> PAD_TABS = Set.of("키패드", "다이얼패드", "keypad", "dialpad", "dial pad");
    private static final Set<String> PAD_IDS = Set.of("dialpad", "dialpad_view", "dialpad_keypad", "dialpad_keypad_container");

    public RequestedApp(String packageName, String label) { this(packageName, label, null, false); }
    RequestedApp(String pkg, String label, String activity, boolean dialpad) {
        this(pkg,label,activity,dialpad,null,BlockRoute.NONE);
    }
    private RequestedApp(String pkg,String label,String activity,boolean dialpad,Intent entryIntent,BlockRoute blockRoute){
        this.entryIntent=entryIntent;this.blockRoute=blockRoute;
        this.packageName = pkg;
        this.label = label;
        this.activity = activity;
        this.dialpad = dialpad;
    }

    static List<RequestedApp> installed(Context context) {
        var pm = context.getPackageManager();
        List<RequestedApp> apps = new ArrayList<>();
        for (var info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (info.activityInfo != null && !context.getPackageName().equals(info.activityInfo.packageName))
                apps.add(new RequestedApp(info.activityInfo.packageName, String.valueOf(info.loadLabel(pm)), info.activityInfo.name, false));
        }
        return apps;
    }

    public static RequestedApp find(Context context, String goal) {
        var apps = withAliases(installed(context));
        RequestedApp explicit=resolve(goal,apps,needsDialpad(goal)?defaultDialpad(context):null);
        if(!isNumberBlock(goal))return explicit;
        if(explicit!=null&&!Set.of("전화","phone","연락처","contacts").contains(shortName(explicit.label).toLowerCase(Locale.ROOT)))return explicit;
        if(savedContactBlock(goal)){
            RequestedApp contacts=fromIntent(context,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),"연락처",BlockRoute.CONTACTS);
            if(contacts!=null)return contacts;
        }
        TelecomManager telecom=context.getSystemService(TelecomManager.class);
        if(telecom!=null)try{
            RequestedApp manager=fromIntent(context,telecom.createManageBlockedNumbersIntent(),"수신 차단",BlockRoute.MANAGEMENT);
            if(manager!=null)return manager;
        }catch(SecurityException|UnsupportedOperationException ignored){ }
        if(telecom!=null){
            String phone=telecom.getDefaultDialerPackage();
            if(phone!=null){
                RequestedApp fallback=fromIntent(context,context.getPackageManager().getLaunchIntentForPackage(phone),"전화",BlockRoute.PHONE_SETTINGS);
                if(fallback!=null)return fallback;
            }
        }
        return explicit;
    }
    static boolean isNumberBlock(String goal){
        if(goal==null)return false;
        String text=goal.toLowerCase(Locale.ROOT).replaceAll("\\s+","");
        if(text.matches(".*(?:차단해제|차단하지|차단말고|차단풀|알림|메시지|문자|계좌|카드|비밀번호|인증번호|sms|accountnumber|unblock|don.tblock|notification).*"))return false;
        return (text.matches(".*(?:전화번호|전화|통화|수신|연락처|phone|call|contact).*") || goal.trim().matches("^번호(?:를|을|\\s).*"))
            &&text.matches(".*(?:차단|수신거부|block).*");
    }
    static boolean savedContactBlock(String goal){
        String text=goal.toLowerCase(Locale.ROOT).replaceAll("\\s+","");
        return !text.matches(".*(?:미저장|저장되지|저장안|연락처에없는|모르는|unknown|unsaved|notincontacts).*")
            &&text.matches(".*(?:연락처|저장된|저장한|contacts?|savednumber).*");
    }
    private static RequestedApp fromIntent(Context context,Intent intent,String label,BlockRoute route){
        if(intent==null)return null;
        var info=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        if(info==null||info.activityInfo==null||!info.activityInfo.exported||"android".equals(info.activityInfo.packageName))return null;
        ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
        return new RequestedApp(component.getPackageName(),label,component.getClassName(),false,new Intent(intent).setComponent(component),route);
    }

    private static RequestedApp defaultDialpad(Context context) {
        var telecom = context.getSystemService(TelecomManager.class);
        String pkg = telecom == null ? null : telecom.getDefaultDialerPackage();
        var intent = new Intent(Intent.ACTION_DIAL).setPackage(pkg);
        var info = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null || "android".equals(info.activityInfo.packageName)) return null;
        return new RequestedApp(info.activityInfo.packageName, "전화", null, true);
    }

    Intent intent(PackageManager pm) {
        if(entryIntent!=null)return new Intent(entryIntent);
        if (dialpad) return new Intent(Intent.ACTION_DIAL).setPackage(packageName);
        if (activity != null) return Intent.makeMainActivity(new ComponentName(packageName, activity));
        return pm.getLaunchIntentForPackage(packageName);
    }

    boolean reached(ScreenSnapshot screen) {
        if (screen == null || screen.activeWindowId == null || screen.activeWindowId < 0) return false;
        if(blockRoute==BlockRoute.MANAGEMENT)return blockedNumbersScreen(screen);
        if(!packageName.equals(screen.packageName))return false;
        if (!dialpad) return true;
        // A contacts list can share the dialer's package. Require the visible pad or its selected tab.
        Set<String> keys = new HashSet<>();
        for (var node : screen.nodes) {
            if (!node.visibleToUser || node.sensitiveInput || node.windowId != screen.activeWindowId || !packageName.equals(node.packageName)) continue;
            if (node.selected && (padTab(node.text) || padTab(node.contentDescription))) return true;
            String id = node.resourceId == null ? "" : node.resourceId.substring(node.resourceId.lastIndexOf('/') + 1);
            if (PAD_IDS.contains(id)) return true;
            if (node.clickable && node.text != null && node.text.length()==1 && "0123456789".contains(node.text)) keys.add(node.text);
        }
        return keys.size() >= 8;
    }

    /** Samsung's Telecom entry delegates to TelephonyUI; only the block-list surface proves arrival. */
    private boolean blockedNumbersScreen(ScreenSnapshot screen){
        boolean samsung="com.samsung.android.app.telephonyui".equals(screen.packageName);
        if(!packageName.equals(screen.packageName)&&!samsung)return false;
        for(var n:screen.nodes){
            if(!n.visibleToUser||n.sensitiveInput||n.windowId!=screen.activeWindowId||!screen.packageName.equals(n.packageName))continue;
            if(samsung&&(screen.packageName+":id/block_numbers_layout").equals(n.resourceId))return true;
            String title=n.text==null?"":n.text.trim().toLowerCase(Locale.ROOT);
            if(!n.clickable&&(n.heading||n.resourceId!=null&&n.resourceId.endsWith("/action_bar_title"))
                    &&Set.of("수신 차단","수신 차단 목록","차단된 번호","전화번호 차단","blocked numbers","block numbers").contains(title))return true;
        }
        return false;
    }
    String blockInstructions(){
        return switch(blockRoute){
            case MANAGEMENT -> "수신 차단 화면의 ‘전화번호 추가’에서 번호를 입력해 주세요. 저장된 번호라면 ‘연락처’에서 선택할 수 있어요.";
            case CONTACTS -> "연락처에서 차단할 사람을 직접 선택하고, 더보기에서 연락처 차단을 눌러 주세요.";
            case PHONE_SETTINGS -> "전화 앱의 더보기에서 설정, 수신 차단을 차례로 열어 주세요. 번호는 수신 차단 화면에서 입력해 주세요.";
            default -> throw new IllegalStateException("No number blocking route");
        };
    }

    JSONObject dialpadTab(JSONObject current)throws JSONException {
        JSONObject tab=null;
        JSONArray nodes=current.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.getJSONObject(i);
            if(!"accessibility".equals(node.optString("source"))||!"tab".equals(node.optString("role"))
                    ||!node.optBoolean("clickable")||!padTab(node.optString("label")))continue;
            if(tab!=null)return null;
            tab=node;
        }
        return tab!=null&&Boolean.FALSE.equals(tab.opt("selected"))?tab:null;
    }

    private static boolean padTab(String value) {
        if (value == null) return false;
        return PAD_TABS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    static boolean needsDialpad(String goal) {
        if (goal == null) return false;
        String compact = goal.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("전화앱말고") || compact.contains("전화앱대신") || compact.contains("전화말고")) return false;
        boolean phoneContext = compact.contains("전화") || compact.contains("통화") || compact.contains("phone") || compact.contains("dial");
        if (!phoneContext && (compact.contains("비밀번호") || compact.contains("잠금") || compact.contains("도어락")
                || compact.contains("계산기") || compact.contains("인증"))) return false;
        return compact.contains("단축번호") || compact.contains("단축다이얼") || compact.contains("다이얼패드")
            || compact.contains("dialpad") || compact.contains("speeddial")
            || ((compact.contains("키패드") || compact.contains("keypad"))
                && (phoneContext || compact.contains("즐겨찾기")));
    }
    /** Korean names people say for apps whose launcher label is English. Keyed by package, never by label. */
    private static final Map<String,List<String>> ALIASES=Map.ofEntries(
        Map.entry("com.google.android.youtube",List.of("유튜브")),
        Map.entry("com.google.android.apps.youtube.music",List.of("유튜브 뮤직","유튜브뮤직")),
        Map.entry("com.android.chrome",List.of("크롬")),
        Map.entry("com.google.android.gm",List.of("지메일")),
        Map.entry("com.google.android.apps.maps",List.of("구글 지도","구글지도","구글 맵","구글맵")),
        Map.entry("com.google.android.apps.photos",List.of("구글 포토","구글포토")),
        Map.entry("com.android.vending",List.of("플레이 스토어","플레이스토어")),
        Map.entry("com.instagram.android",List.of("인스타그램","인스타")),
        Map.entry("com.netflix.mediaclient",List.of("넷플릭스")),
        Map.entry("com.spotify.music",List.of("스포티파이")),
        Map.entry("com.facebook.katana",List.of("페이스북")));
    static List<RequestedApp> withAliases(List<RequestedApp> apps){
        List<RequestedApp> all=new ArrayList<>(apps);
        for(RequestedApp app:apps)for(String alias:ALIASES.getOrDefault(app.packageName,List.of()))all.add(new RequestedApp(app.packageName,alias,app.activity,false));
        return all;
    }
    static String shortName(String label){return label.split("\\s*[-–—:(（]",2)[0].trim();}
    static RequestedApp resolve(String goal,List<RequestedApp> apps){
        return resolve(goal, apps, null);
    }
    static RequestedApp resolve(String goal, List<RequestedApp> apps, RequestedApp dialer) {
        if (goal == null) return null;
        RequestedApp best = null;
        int bestRank = -1, length = 0;
        boolean ambiguous = false;
        for (RequestedApp app : apps) {
            String name = shortName(app.label);
            if (name.length() < 2) continue;
            var mention = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name)
                + "(?=$|\\s|에서|으로|을|를|에|켜|열|앱)", Pattern.CASE_INSENSITIVE).matcher(goal);
            while (mention.find()) {
                String suffix = goal.substring(mention.end()).trim();
                if (NEGATED_APP.matcher(suffix).find()) continue;
                int rank = APP_CONTEXT.matcher(suffix).find() ? 1 : 0;
                if (rank > bestRank || rank == bestRank && name.length() > length) {
                    best = app; bestRank = rank; length = name.length(); ambiguous = false;
                } else if (rank == bestRank && name.length() == length && best != null
                        && !(best.packageName.equals(app.packageName) && Objects.equals(best.activity, app.activity))) {
                    ambiguous = true;
                }
            }
        }
        if (ambiguous) return null;
        if (!needsDialpad(goal)) return best;
        if (best == null) return dialer;
        String name = shortName(best.label).toLowerCase(Locale.ROOT);
        // In "전화번호 키패드에서 연락처 즐겨찾기", 연락처 is the object of the task.
        if (bestRank == 0 && Set.of("연락처", "contacts").contains(name)) return dialer;
        if (dialer != null && dialer.packageName.equals(best.packageName)
                && (bestRank == 0 || Set.of("전화", "phone", "dialer").contains(name))) return dialer;
        return best;
    }
}
