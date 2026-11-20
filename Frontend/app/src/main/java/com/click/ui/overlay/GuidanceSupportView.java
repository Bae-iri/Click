package com.click.ui.overlay;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.click.data.local.GuidancePreferences;
import com.click.ui.common.ClickUi;

/** One optional help surface. Editing and feedback expand only when requested. */
public final class GuidanceSupportView extends ScrollView {
    public interface Listener {
        void close();void retry();void revise(String text);void enqueue(String text);
        String feedback(GuidancePreferences.Feedback feedback,boolean remember);
    }
    private final TextView status;
    private final Button save;
    private final Button queue;
    private final LinearLayout requestEditor;
    private final EditText requestInput;
    private final java.util.Map<View,Boolean> lockedControls=new java.util.LinkedHashMap<>();
    private boolean saving;
    public boolean isSaving(){return saving;}
    public void editRequest(String draft){if(saving)return;requestInput.setText(draft==null?"":draft);requestEditor.setVisibility(VISIBLE);}
    public GuidanceSupportView(Context context,String request,String evidence,boolean canRetry,boolean canRevise,
            boolean remembers,boolean detailed,Listener listener){
        super(context);setFillViewport(false);setBackground(ClickUi.outlined(0xFFFFFFFF,ClickUi.BORDER,dp(22),dp(1)));setElevation(dp(12));
        LinearLayout body=column();body.setPadding(dp(20),dp(16),dp(20),dp(16));addView(body);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=ClickUi.sectionTitle(context,"안내 확인");header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=ClickUi.secondaryButton(context,"닫기");requestClose=close;close.setBackgroundColor(android.graphics.Color.TRANSPARENT);close.setMinWidth(0);close.setMinimumWidth(0);close.setTextColor(ClickUi.SUBTEXT);close.setTextSize(com.click.ui.common.ClickTextScale.size(context,14));close.setPadding(dp(8),0,dp(8),0);close.setOnClickListener(view->{if(!saving)listener.close();});header.addView(close);body.addView(header);
        if(request!=null&&!request.isBlank()){TextView summary=ClickUi.body(context,(canRetry?"잠시 멈춤 · ":"")+request.substring(0,Math.min(160,request.length())));summary.setTextSize(com.click.ui.common.ClickTextScale.size(context,14));body.addView(summary,ClickUi.wrap(8));}
        String full=evidence==null?GuidanceExplanation.DEFAULT:evidence;String shortReason=full.split("(?<=[.!?])\\s+",2)[0];
        TextView reason=ClickUi.body(context,shortReason);reason.setTextColor(ClickUi.TEXT);body.addView(reason,ClickUi.wrap(12));
        TextView detail=ClickUi.body(context,(full.equals(shortReason)?"":full.substring(shortReason.length()).trim()+"\n")+GuidanceExplanation.LIMITS);
        detail.setVisibility(detailed?VISIBLE:GONE);body.addView(detail,ClickUi.wrap(8));
        LinearLayout actions=new LinearLayout(context);
        Button retry=ClickUi.primaryButton(context,"다시 확인");retry.setVisibility(canRetry?VISIBLE:GONE);retry.setOnClickListener(view->{if(!saving&&canRetry)listener.retry();});actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));
        Button edit=ClickUi.secondaryButton(context,canRevise?"요청 수정":canRetry?"다음 요청 작성":"새 요청 작성");LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=dp(canRetry?8:0);actions.addView(edit,right);body.addView(actions,ClickUi.wrap(16));
        LinearLayout editor=column();editor.setVisibility(GONE);body.addView(editor,ClickUi.wrap(12));
        EditText input=ClickUi.input(context,canRevise?"수정할 요청":"새로 요청할 내용",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setText(request==null?"":request);input.setSingleLine(false);input.setMinLines(2);input.setMaxLines(4);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});editor.addView(input);
        requestEditor=editor;requestInput=input;
        editor.addView(ClickUi.body(context,canRevise?"요청을 고쳐도 이미 바꾼 설정은 그대로예요.":canRetry?"현재 요청은 바뀌지 않아요. 작성한 내용은 다음 작업으로 추가해요.":"작성한 내용으로 새 안내를 요청해요."),ClickUi.wrap(8));
        save=ClickUi.primaryButton(context,canRevise?"수정한 요청 확인":canRetry?"다음 작업으로 추가":"새 요청 보내기");save.setOnClickListener(view->{if(saving)return;String text=input.getText().toString().trim();if(!text.isEmpty()){if(canRevise)listener.revise(text);else listener.enqueue(text);}});editor.addView(save,ClickUi.wrap(12));
        // Only show a second action when it is actually different from the primary one.
        queue=canRevise?ClickUi.secondaryButton(context,"다음 작업으로 추가"):null;
        if(queue!=null){queue.setOnClickListener(view->{if(saving)return;String text=input.getText().toString().trim();if(!text.isEmpty())listener.enqueue(text);});editor.addView(queue,ClickUi.wrap(8));}
        for(View control:new View[]{close,retry,edit,save,input})lockedControls.put(control,control.isEnabled());
        if(queue!=null)lockedControls.put(queue,queue.isEnabled());
        input.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){updateSubmissionState();}
            public void afterTextChanged(android.text.Editable value){}
        });
        updateSubmissionState();
        edit.setOnClickListener(view->{if(!saving)editor.setVisibility(editor.getVisibility()==VISIBLE?GONE:VISIBLE);});
        Button evaluate=ClickUi.secondaryButton(context,"안내 평가·방식 바꾸기");evaluate.setBackgroundColor(android.graphics.Color.TRANSPARENT);evaluate.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);evaluate.setTextSize(com.click.ui.common.ClickTextScale.size(context,13));evaluate.setPadding(0,0,0,0);body.addView(evaluate,ClickUi.wrap(8));
        LinearLayout feedback=column();feedback.setVisibility(GONE);body.addView(feedback,ClickUi.wrap(8));
        CheckBox remember=new CheckBox(context);remember.setText("다음 안내에도 기억하기");remember.setChecked(remembers);remember.setMinHeight(dp(48));feedback.addView(remember);
        lockedControls.put(evaluate,evaluate.isEnabled());lockedControls.put(remember,remember.isEnabled());
        feedback.addView(ClickUi.body(context,"선택은 이번 안내에 바로 적용해요. 기억하기를 켜면 읽기 속도와 설명 방식만 이 기기에 저장해요. 끄면 기억한 선택을 지워요."),ClickUi.wrap(4));
        status=ClickUi.body(context,"");status.setVisibility(GONE);status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        String[] labels={"위치가 틀려요","설명이 어려워요","천천히 읽어주세요","설명 더 보기","간단히 보기"};
        GuidancePreferences.Feedback[] values=GuidancePreferences.Feedback.values();
        for(int i=0;i<labels.length;i++){
            final var value=values[i];Button choice=ClickUi.secondaryButton(context,labels[i]);choice.setOnClickListener(view->{
                if(saving)return;
                if(value==GuidancePreferences.Feedback.MORE_DETAIL||value==GuidancePreferences.Feedback.UNCLEAR)detail.setVisibility(VISIBLE);
                if(value==GuidancePreferences.Feedback.LESS_DETAIL)detail.setVisibility(GONE);
                status.setText(listener.feedback(value,remember.isChecked()));status.setVisibility(VISIBLE);
            });feedback.addView(choice,ClickUi.wrap(6));lockedControls.put(choice,choice.isEnabled());
        }
        evaluate.setOnClickListener(view->{if(!saving)feedback.setVisibility(feedback.getVisibility()==VISIBLE?GONE:VISIBLE);});body.addView(status,ClickUi.wrap(8));
    }
    private void updateSubmissionState(){boolean enabled=!saving&&!requestInput.getText().toString().trim().isEmpty();save.setEnabled(enabled);if(queue!=null)queue.setEnabled(enabled);}
    public void error(String message){saving=false;for(var control:lockedControls.entrySet())control.getKey().setEnabled(control.getValue());updateSubmissionState();status.setText(message);status.setVisibility(VISIBLE);}
    public void saving(){saving=true;for(View control:lockedControls.keySet())control.setEnabled(false);status.setText("수정한 요청을 확인하고 있어요…");status.setVisibility(VISIBLE);}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_BACK){if(!saving&&event.getAction()==android.view.KeyEvent.ACTION_UP)requestClose.performClick();return true;}return super.dispatchKeyEvent(event);
    }
    private Button requestClose;
    private LinearLayout column(){LinearLayout layout=new LinearLayout(getContext());layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private int dp(int value){return ClickUi.dp(getContext(),value);}
    @Override protected void onMeasure(int width,int height){int limit=Math.round(getResources().getDisplayMetrics().heightPixels*.78f);if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)limit=Math.min(limit,MeasureSpec.getSize(height));super.onMeasure(width,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));}
}
