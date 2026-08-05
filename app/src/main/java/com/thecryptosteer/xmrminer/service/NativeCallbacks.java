package com.thecryptosteer.xmrminer.service;

import androidx.annotation.Keep;

@Keep
public interface NativeCallbacks {
    void onState(String state, String message);
    void onStats(String json);
    void onLog(String level, String message);
    void onAccounting(long userNanos, long donationNanos);
}
