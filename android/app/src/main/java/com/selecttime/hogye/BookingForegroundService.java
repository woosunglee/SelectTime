package com.selecttime.hogye;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Keeps a foreground notification while booking UI / automation runs.
 */
public class BookingForegroundService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotifyHelper.ensureChannel(this);
        startForeground(
                NotifyHelper.NOTIF_STATUS,
                NotifyHelper.foregroundNotification(this, getString(R.string.notif_starting))
        );
        // Auto-stop after a while; BookingActivity owns the real work
        new android.os.Handler(getMainLooper()).postDelayed(this::stopSelf, 15 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
