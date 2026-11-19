package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import com.click.data.snapshot.*;
import org.json.*;
import java.util.List;

/** Explicit, one-shot local OCR only inside small unnamed native controls. */
public final class AppTaskVision {
    public interface Callback {void success(ScreenSnapshot screen,JSONObject enriched);void failure(String reason);}
    public static void capture(AccessibilityService service,ScreenSnapshot screen,Callback callback){
        if(service==null||screen==null||Build.VERSION.SDK_INT<30){callback.failure("이 기기에서는 추가 화면 읽기를 사용할 수 없어요.");return;}
        try{if(!"public".equals(AppTaskObservation.capture(screen).optString("status"))||!VisionSafetyPolicy.assess(screen).allowed){callback.failure("입력·인증 등 보호가 필요한 화면은 추가로 읽지 않아요.");return;}}
        catch(Exception e){callback.failure("현재 화면을 확인하지 못했어요.");return;}
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout=()->{if(finished.compareAndSet(false,true))callback.failure("화면 읽기가 지연됐어요. 직접 진행하거나 다시 확인해 주세요.");};
        main.postDelayed(timeout,12000);
        Api30.capture(service,screen,new Callback(){
            public void success(ScreenSnapshot s,JSONObject enriched){if(finished.compareAndSet(false,true)){main.removeCallbacks(timeout);callback.success(s,enriched);}}
            public void failure(String reason){if(finished.compareAndSet(false,true)){main.removeCallbacks(timeout);callback.failure(reason);}}
        });
    }
    @androidx.annotation.RequiresApi(30)
    private static final class Api30 {
        static void capture(AccessibilityService service,ScreenSnapshot screen,Callback callback){
            try{service.takeScreenshot(Display.DEFAULT_DISPLAY,service.getMainExecutor(),new AccessibilityService.TakeScreenshotCallback(){
                public void onFailure(int code){callback.failure("보호된 화면이거나 캡처를 사용할 수 없어요.");}
                public void onSuccess(AccessibilityService.ScreenshotResult result){
                    Bitmap full=null,masked=null;
                    try(HardwareBuffer buffer=result.getHardwareBuffer()){
                        Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware!=null){full=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}
                        if(full==null||full.getWidth()!=screen.screenWidth||full.getHeight()!=screen.screenHeight)throw new IllegalStateException();
                        JSONObject projected=AppTaskObservation.capture(screen);
                        ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
                        if(current==null||!projected.optString("fingerprint").equals(AppTaskObservation.capture(current).optString("fingerprint")))throw new IllegalStateException();
                        masked=Bitmap.createBitmap(full.getWidth(),full.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(masked);canvas.drawColor(Color.BLACK);
                        JSONArray nodes=projected.getJSONArray("nodes");int crops=0;
                        for(int i=0;i<nodes.length();i++){
                            JSONObject n=nodes.getJSONObject(i);JSONArray b=n.getJSONArray("bounds");int l=b.getInt(0),t=b.getInt(1),r=b.getInt(2),bottom=b.getInt(3);
                            if(!n.optBoolean("clickable")||n.optBoolean("editable")||n.optBoolean("scrollable")||!n.optString("label").isEmpty()
                                    ||(long)(r-l)*(bottom-t)>(long)full.getWidth()*full.getHeight()/8)continue;
                            canvas.save();canvas.clipRect(l,t,r,bottom);canvas.drawBitmap(full,0,0,null);canvas.restore();if(++crops>=12)break;
                        }
                        full.recycle();full=null;
                        if(crops==0){masked.recycle();callback.failure("추가로 읽을 이름 없는 버튼이 없어요.");return;}
                        Bitmap image=masked;VisionOcrEngine engine=new VisionOcrEngine();
                        engine.recognize(image,screen,new VisionOcrEngine.Callback(){
                            public void onFailure(String reason,Throwable error){image.recycle();engine.close();callback.failure("화면 글자를 읽지 못했어요. 직접 진행하거나 다시 확인해 주세요.");}
                            public void onSuccess(List<VisionOcrElement> elements,long processingTimeMs){
                                image.recycle();engine.close();
                                try{
                                    ScreenSnapshot now=ClickAccessibilityService.freshSnapshot();
                                    if(now==null||!projected.optString("fingerprint").equals(AppTaskObservation.capture(now).optString("fingerprint")))throw new IllegalStateException();
                                    callback.success(screen,enrich(projected,elements));
                                }catch(Exception e){callback.failure("읽는 동안 화면이 바뀌었어요. 현재 화면에서 다시 확인해 주세요.");}
                            }
                        });
                    }catch(Exception error){if(full!=null&&!full.isRecycled())full.recycle();if(masked!=null&&!masked.isRecycled())masked.recycle();callback.failure("추가 화면 읽기를 완료하지 못했어요.");}
                }
            });}catch(RuntimeException e){callback.failure("화면 읽기 권한을 사용할 수 없어요.");}
        }
    }
    public static JSONObject enrich(JSONObject original,List<VisionOcrElement> elements)throws JSONException {
        JSONObject copy=new JSONObject(original.toString());JSONArray nodes=copy.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i);if(!n.optString("label").isEmpty()||!n.optBoolean("clickable")||n.optBoolean("editable")||n.optBoolean("scrollable"))continue;
            JSONArray b=n.getJSONArray("bounds");StringBuilder text=new StringBuilder();
            for(var e:elements){int[] p=e.bounds;String label=AppTaskObservation.label(e.text);if(label.isEmpty()||p==null||p.length!=4||e.confidence<0.65f)continue;
                if(p[0]>=b.getInt(0)&&p[1]>=b.getInt(1)&&p[2]<=b.getInt(2)&&p[3]<=b.getInt(3)){if(text.length()>0)text.append(' ');text.append(label);}}
            String label=AppTaskObservation.label(text.toString());if(!label.isEmpty())n.put("label",label).put("source","ocr");
        }
        return AppTaskObservation.finish(copy);
    }
}
