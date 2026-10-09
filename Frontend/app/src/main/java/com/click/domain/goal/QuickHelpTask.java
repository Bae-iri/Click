package com.click.domain.goal;

/** A help card is an explicit user selection, not a sentence to classify again. */
public enum QuickHelpTask {
    FONT_SIZE("settings.font_size.increase", "글자 크기를 크게 해줘");

    public final String goalType;
    public final String requestText;

    QuickHelpTask(String goalType, String requestText) {
        this.goalType = goalType;
        this.requestText = requestText;
    }

    public static QuickHelpTask fromGoalType(String goalType) {
        for (QuickHelpTask task : values()) if (task.goalType.equals(goalType)) return task;
        return null;
    }
}
