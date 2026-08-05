package com.thecryptosteer.xmrminer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.thecryptosteer.xmrminer.MainActivity;
import com.thecryptosteer.xmrminer.R;
import com.thecryptosteer.xmrminer.data.AccountingWriter;
import com.thecryptosteer.xmrminer.data.MiningStateBus;
import com.thecryptosteer.xmrminer.domain.MinerConfig;
import com.thecryptosteer.xmrminer.domain.MiningSnapshot;
import com.thecryptosteer.xmrminer.domain.MiningState;
import com.thecryptosteer.xmrminer.nativebridge.ConfigJson;
import com.thecryptosteer.xmrminer.nativebridge.NativeMiner;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiningForegroundService extends Service implements NativeCallbacks {
    public static final String ACTION_START = "com.thecryptosteer.xmrminer.action.START";
    public static final String ACTION_PAUSE = "com.thecryptosteer.xmrminer.action.PAUSE";
    public static final String ACTION_RESUME = "com.thecryptosteer.xmrminer.action.RESUME";
    public static final String ACTION_STOP = "com.thecryptosteer.xmrminer.action.STOP";
    public static volatile MinerConfig pendingConfig;
    private static volatile MiningForegroundService activeInstance;

    private static final String CHANNEL_ID = "active_mining_v1";
    private static final int NOTIFICATION_ID = 4102;
    private static final long WAKE_LOCK_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MILLIS = 9L * 60L * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private long nativeHandle;
    private boolean nativeStarted;
    private MiningState terminalState;
    private PowerManager.WakeLock wakeLock;
    private PowerManager powerManager;
    private ConnectivityManager connectivityManager;
    private MinerConfig activeConfig;
    private boolean pausedByConstraint;
    private boolean thermalConstraintLatched;
    private int batteryPercent;
    private boolean charging;
    private int thermalStatus;
    private long lastNotificationAtMillis;

    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            MiningSnapshot snapshot = MiningStateBus.INSTANCE.getSnapshot().getValue();
            if (!running.get() || !nativeStarted || snapshot.getState() == MiningState.PAUSED) return;
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
            main.postDelayed(this, WAKE_LOCK_RENEW_MILLIS);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryPercent = level >= 0 ? Math.round(level * 100f / Math.max(1, scale)) : 0;
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            evaluateConstraints();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            evaluateConstraints();
        }
    };

    private final PowerManager.OnThermalStatusChangedListener thermalListener = status -> {
        thermalStatus = status;
        evaluateConstraints();
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network network) { main.post(MiningForegroundService.this::evaluateConstraints); }
        @Override public void onLost(@NonNull Network network) { main.post(MiningForegroundService.this::evaluateConstraints); }
        @Override public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            main.post(MiningForegroundService.this::evaluateConstraints);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createChannel();
        nativeHandle = NativeMiner.INSTANCE.create(this);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        thermalStatus = powerManager.getCurrentThermalStatus();
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptoSteer:ActiveMining");
        wakeLock.setReferenceCounted(false);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        powerManager.addThermalStatusListener(getMainExecutor(), thermalListener);
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }


    public static void updateDonationBasisPoints(int basisPoints) {
        MiningForegroundService instance = activeInstance;
        if (instance == null || instance.nativeHandle == 0L) return;
        instance.main.post(() -> {
            try {
                NativeMiner.INSTANCE.updateDonationBasisPoints(instance.nativeHandle, basisPoints);
            } catch (RuntimeException exception) {
                MiningStateBus.INSTANCE.appendLog("WARN", "Unable to update the active donation tier: " + exception.getMessage());
            }
        });
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) startRequested();
        else if (ACTION_PAUSE.equals(action)) pauseRequested(false);
        else if (ACTION_RESUME.equals(action)) resumeRequested(false);
        else if (ACTION_STOP.equals(action)) stopRequested();
        return START_NOT_STICKY;
    }

    private void startRequested() {
        if (running.get()) return;
        MinerConfig config = pendingConfig;
        pendingConfig = null;
        if (config == null) {
            MiningStateBus.state(MiningState.ERROR, "No validated mining configuration was supplied.");
            stopSelf();
            return;
        }
        activeConfig = config;
        terminalState = null;
        pausedByConstraint = false;
        thermalConstraintLatched = false;
        nativeStarted = false;
        running.set(true);
        startForeground(NOTIFICATION_ID, buildNotification("Starting", 0.0));
        MiningStateBus.state(MiningState.STARTING, "");
        evaluateConstraints();
        if (!running.get()) return;
        if (constraintsBlocked()) {
            pausedByConstraint = true;
            MiningStateBus.state(MiningState.PAUSED, constraintMessage());
            updateNotification();
        } else {
            startNativeEngine();
        }
    }

    private void startNativeEngine() {
        if (!running.get() || nativeStarted || activeConfig == null) return;
        if (constraintsBlocked()) {
            pausedByConstraint = true;
            MiningStateBus.state(MiningState.PAUSED, constraintMessage());
            updateNotification();
            return;
        }
        holdWakeLock();
        MiningStateBus.state(MiningState.STARTING, "");
        final boolean started;
        try {
            started = NativeMiner.INSTANCE.start(nativeHandle, ConfigJson.encode(activeConfig));
        } catch (RuntimeException exception) {
            terminateSession(MiningState.ERROR, "Native mining engine failed to start: " + exception.getMessage());
            return;
        }
        if (!started) {
            terminateSession(MiningState.ERROR, "Native mining engine rejected the configuration.");
            return;
        }
        nativeStarted = true;
    }

    private void pauseRequested(boolean automatic) {
        if (!running.get()) return;
        if (!nativeStarted) {
            pausedByConstraint = automatic;
            MiningStateBus.state(MiningState.PAUSED, automatic ? constraintMessage() : "Paused by user.");
            updateNotification();
            return;
        }
        try {
            if (NativeMiner.INSTANCE.pause(nativeHandle)) {
                pausedByConstraint = automatic;
                releaseWakeLock();
                MiningStateBus.state(MiningState.PAUSED, automatic ? constraintMessage() : "Paused by user.");
                updateNotification();
            }
        } catch (RuntimeException exception) {
            terminateSession(MiningState.ERROR, "Native pause failed: " + exception.getMessage());
        }
    }

    private void resumeRequested(boolean automatic) {
        if (!running.get()) return;
        if (automatic && !pausedByConstraint) return;
        if (constraintsBlocked()) {
            pausedByConstraint = true;
            MiningStateBus.state(MiningState.PAUSED, constraintMessage());
            MiningStateBus.appendLog("INFO", "Resume deferred because a selected device constraint is still active.");
            updateNotification();
            return;
        }
        if (!nativeStarted) {
            pausedByConstraint = false;
            startNativeEngine();
            return;
        }
        try {
            if (NativeMiner.INSTANCE.resume(nativeHandle)) {
                pausedByConstraint = false;
                holdWakeLock();
                MiningStateBus.state(MiningState.CONNECTING, "");
                updateNotification();
            }
        } catch (RuntimeException exception) {
            terminateSession(MiningState.ERROR, "Native resume failed: " + exception.getMessage());
        }
    }

    private void stopRequested() {
        terminateSession(MiningState.STOPPED, "");
    }

    private void terminateSession(MiningState finalState, String message) {
        terminalState = finalState;
        boolean wasRunning = running.getAndSet(false);
        if (wasRunning && nativeStarted) {
            try {
                NativeMiner.INSTANCE.stop(nativeHandle);
            } catch (RuntimeException exception) {
                MiningStateBus.appendLog("ERROR", "Native shutdown failed: " + exception.getMessage());
            }
        }
        nativeStarted = false;
        activeConfig = null;
        pausedByConstraint = false;
        thermalConstraintLatched = false;
        releaseWakeLock();
        MiningStateBus.state(finalState, message == null ? "" : message);
        if (finalState == MiningState.ERROR && message != null && !message.isBlank()) {
            MiningStateBus.appendLog("ERROR", message);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void holdWakeLock() {
        main.removeCallbacks(renewWakeLock);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
        main.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MILLIS);
    }

    private void releaseWakeLock() {
        main.removeCallbacks(renewWakeLock);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void evaluateConstraints() {
        MiningStateBus.updateDevice(thermalStatus, batteryPercent, charging);
        MinerConfig config = activeConfig;
        if (!running.get() || config == null) return;
        int stopThermal = config.getTuning().getStopThermalStatus();
        int resumeThermal = Math.min(config.getTuning().getResumeThermalStatus(), stopThermal - 1);
        if (thermalStatus >= stopThermal) thermalConstraintLatched = true;
        else if (thermalStatus <= resumeThermal) thermalConstraintLatched = false;

        if (thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY) {
            terminateSession(MiningState.ERROR, "Emergency thermal status reached; mining stopped.");
            return;
        }

        boolean blocked = constraintsBlocked();
        MiningState state = MiningStateBus.INSTANCE.getSnapshot().getValue().getState();
        if (blocked && state != MiningState.PAUSED) {
            pauseRequested(true);
        } else if (blocked && !nativeStarted) {
            pausedByConstraint = true;
            MiningStateBus.state(MiningState.PAUSED, constraintMessage());
        } else if (!blocked && pausedByConstraint) {
            resumeRequested(true);
        }
        updateNotification();
    }

    private boolean constraintsBlocked() {
        MinerConfig config = activeConfig;
        if (config == null) return false;
        return thermalConstraintLatched
            || (config.getTuning().getMineOnlyWhileCharging() && !charging)
            || batteryPercent < config.getTuning().getPauseBelowBatteryPercent()
            || (config.getTuning().getPauseWhenScreenOn() && powerManager.isInteractive())
            || (config.getTuning().getWifiOnly() && !isWifiConnected());
    }

    private String constraintMessage() {
        MinerConfig config = activeConfig;
        if (config == null) return "Paused by device constraint.";
        if (thermalConstraintLatched) return "Paused until the device cools to the configured resume level.";
        if (config.getTuning().getMineOnlyWhileCharging() && !charging) return "Waiting for external power.";
        if (batteryPercent < config.getTuning().getPauseBelowBatteryPercent()) return "Battery is below the configured mining threshold.";
        if (config.getTuning().getPauseWhenScreenOn() && powerManager.isInteractive()) return "Paused while the screen is on.";
        if (config.getTuning().getWifiOnly() && !isWifiConnected()) return "Waiting for a Wi-Fi connection.";
        return "Paused by device constraint.";
    }

    private boolean isWifiConnected() {
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivityManager.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override public void onState(String state, String message) {
        main.post(() -> {
            MiningState parsed;
            try { parsed = MiningState.valueOf(state); } catch (IllegalArgumentException ex) { parsed = MiningState.ERROR; }
            if (terminalState != null) return;
            if (parsed == MiningState.ERROR) {
                terminateSession(MiningState.ERROR, message == null ? "Native mining failure." : message);
                return;
            }
            MiningStateBus.state(parsed, message == null ? "" : message);
            updateNotification();
        });
    }

    @Override public void onStats(String json) {
        main.post(() -> {
            if (!running.get() || terminalState != null) return;
            try { MiningStateBus.updateFromJson(json); }
            catch (RuntimeException ex) { MiningStateBus.appendLog("ERROR", "Invalid native statistics frame: " + ex.getMessage()); }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastNotificationAtMillis >= 1000L) {
                lastNotificationAtMillis = now;
                updateNotification();
            }
        });
    }

    @Override public void onLog(String level, String message) {
        main.post(() -> MiningStateBus.appendLog(level, message));
    }

    @Override public void onAccounting(long userNanos, long donationNanos) {
        AccountingWriter.persist(this, userNanos, donationNanos);
    }

    private void updateNotification() {
        if (!running.get()) return;
        MiningSnapshot snapshot = MiningStateBus.INSTANCE.getSnapshot().getValue();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot.getState().name().replace('_', ' '), snapshot.getHashrate()));
    }

    private Notification buildNotification(String state, double hashrate) {
        PendingIntent content = PendingIntent.getActivity(this, 10, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = actionIntent(ACTION_PAUSE, 11);
        PendingIntent resume = actionIntent(ACTION_RESUME, 12);
        PendingIntent stop = actionIntent(ACTION_STOP, 13);
        MiningSnapshot snapshot = MiningStateBus.INSTANCE.getSnapshot().getValue();
        boolean paused = snapshot.getState() == MiningState.PAUSED;
        String detail = String.format(Locale.US, "%.1f H/s • %d workers • %s", hashrate, snapshot.getWorkers(), thermalLabel(thermalStatus));
        String pool = snapshot.getActivePool().isBlank() ? "Connecting to pool" : snapshot.getActivePool();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cryptosteer)
            .setContentTitle("CryptoSteer • " + state)
            .setContentText(detail)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detail + "\n" + pool))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, paused ? "Resume" : "Pause", paused ? resume : pause)
            .addAction(0, "Stop", stop)
            .build();
    }

    private static String thermalLabel(int status) {
        return switch (status) {
            case PowerManager.THERMAL_STATUS_NONE -> "thermal none";
            case PowerManager.THERMAL_STATUS_LIGHT -> "thermal light";
            case PowerManager.THERMAL_STATUS_MODERATE -> "thermal moderate";
            case PowerManager.THERMAL_STATUS_SEVERE -> "thermal severe";
            case PowerManager.THERMAL_STATUS_CRITICAL -> "thermal critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY -> "thermal emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN -> "thermal shutdown";
            default -> "thermal unknown";
        };
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, NotificationActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        running.set(false);
        terminalState = terminalState == null ? MiningState.STOPPED : terminalState;
        main.removeCallbacksAndMessages(null);
        if (nativeHandle != 0L) {
            try {
                NativeMiner.INSTANCE.stop(nativeHandle);
            } catch (RuntimeException exception) {
                MiningStateBus.appendLog("ERROR", "Native service stop cleanup failed: " + exception.getMessage());
            }
            try {
                NativeMiner.INSTANCE.destroy(nativeHandle);
            } catch (RuntimeException exception) {
                MiningStateBus.appendLog("ERROR", "Native service destroy cleanup failed: " + exception.getMessage());
            }
            nativeHandle = 0L;
        }
        nativeStarted = false;
        releaseWakeLock();
        try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) { }
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
        if (powerManager != null) powerManager.removeThermalStatusListener(thermalListener);
        if (connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (IllegalArgumentException ignored) { }
        }
        super.onDestroy();
    }
}
