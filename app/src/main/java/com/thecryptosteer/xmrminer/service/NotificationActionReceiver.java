package com.thecryptosteer.xmrminer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;

public final class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Intent service = new Intent(context, MiningForegroundService.class);
        switch (action) {
            case MiningForegroundService.ACTION_PAUSE, MiningForegroundService.ACTION_RESUME, MiningForegroundService.ACTION_STOP -> {
                service.setAction(action);
                context.startService(service);
            }
            default -> { }
        }
    }
}
