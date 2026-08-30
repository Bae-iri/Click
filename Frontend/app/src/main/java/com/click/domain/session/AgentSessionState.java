package com.click.domain.session;

public enum AgentSessionState {
    IDLE,
    LISTENING,
    CREATING_SESSION,
    WAITING_FOR_SCREEN,
    OBSERVING,
    GUIDING,
    WAITING_CONFIRMATION,
    COMPLETED,
    BLOCKED,
    ERROR
}
