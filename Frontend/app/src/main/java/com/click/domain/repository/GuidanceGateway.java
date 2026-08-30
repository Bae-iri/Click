package com.click.domain.repository;

import com.click.data.remote.GuidanceContract;

public interface GuidanceGateway {
    interface Result<T> {
        void onSuccess(T value);
        void onFailure(Throwable error);
    }

    void createSession(GuidanceContract.CreateSessionRequest request,
                       Result<GuidanceContract.CreateSessionResponse> result);

    void observe(String sessionId, GuidanceContract.ObserveRequest request,
                 Result<GuidanceContract.ObserveResponse> result);
}
