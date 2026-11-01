package com.click.ui.home;

import com.click.domain.goal.QuickHelpTask;

/** Stable IDs keep saved favorites independent of their display labels. */
public enum HomeHelpItem {
    KAKAO("kakao", "카카오톡", "사진 보내기를 준비하고 있어요", HomeIconView.Kind.KAKAO, null),
    FONT("font_size", "글씨 크기", "글씨를 키워볼까요?", HomeIconView.Kind.FONT, QuickHelpTask.FONT_SIZE),
    BRIGHTNESS("brightness", "화면 밝기", "화면을 더 밝게 해볼까요?", HomeIconView.Kind.BRIGHTNESS, QuickHelpTask.BRIGHTNESS),
    MEDIA("media_volume", "동영상·음악 소리", "소리를 키워볼까요?", HomeIconView.Kind.MEDIA, QuickHelpTask.MEDIA_VOLUME),
    PHONE("phone", "전화", "전화 도움을 준비하고 있어요", HomeIconView.Kind.PHONE, null),
    TUTORIAL("tutorial", "클릭 튜토리얼", "사용법을 함께 알아봐요!", HomeIconView.Kind.CLICK, null),
    ACCESSIBILITY("accessibility", "접근성 설정 안내", "설정을 함께 해볼까요?", HomeIconView.Kind.ACCESSIBILITY, null);

    public final String id, title, subtitle;
    public final HomeIconView.Kind icon;
    public final QuickHelpTask task;

    HomeHelpItem(String id, String title, String subtitle, HomeIconView.Kind icon, QuickHelpTask task) {
        this.id = id; this.title = title; this.subtitle = subtitle; this.icon = icon; this.task = task;
    }
}
