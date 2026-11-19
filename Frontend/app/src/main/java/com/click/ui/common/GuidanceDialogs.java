package com.click.ui.common;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import com.click.data.local.GuidancePreferences;
import com.click.ui.overlay.GuidanceExplanation;

/** Secondary information uses the existing dialog and stays behind one link. */
public final class GuidanceDialogs {
    private GuidanceDialogs(){}
    public static void capabilities(Activity activity){
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        for(String text:new String[]{GuidanceExplanation.CAPABILITIES,GuidanceExplanation.LIMITS,GuidanceExplanation.BOUNDARIES})
            body.addView(ClickUi.body(activity,text),ClickUi.wrap(12));
        ClickDialog.show(activity,"어떤 도움을 받을 수 있나요?",body,"확인했어요",null,null);
    }
    public static void preferences(Activity activity){
        var prefs=new GuidancePreferences(activity);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.addView(ClickUi.body(activity,"읽기 속도와 설명 선택을 기억해요. 같은 작업에서 설명을 두 번 다시 요청하면 다음부터 이유도 펼쳐 보여요. 이 맞춤 기능은 화면 내용이나 요청을 저장·전송하지 않아요."));
        SwitchCompat remember=new SwitchCompat(activity);remember.setText("내 안내 방식 기억하기");remember.setMinHeight(ClickUi.dp(activity,48));remember.setChecked(prefs.remembers());body.addView(remember,ClickUi.wrap(12));
        TextView status=ClickUi.body(activity,prefs.summary());body.addView(status,ClickUi.wrap(8));
        remember.setOnCheckedChangeListener((view,enabled)->{prefs.remember(enabled);status.setText(enabled?prefs.summary():"기억한 선택을 지웠어요. 다음 작업부터 기본 안내를 사용해요.");});
        var clear=ClickUi.secondaryButton(activity,"기억한 선택 지우기");clear.setOnClickListener(view->{prefs.clearChoices();status.setText("기억한 속도와 설명 방식을 지웠어요. 다음 작업부터 기본 안내를 사용해요.");});body.addView(clear,ClickUi.wrap(12));
        ClickDialog.show(activity,"안내 방식",body,"닫기",null,null);
    }
}
