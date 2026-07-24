package com.selecttime.hogye;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Keeps a foreground notification while booking UI / automation runs.
 */
public class BookingForegroundService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopSelf;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotifyHelper.ensureChannel(this);
        startForeground(
                NotifyHelper.NOTIF_STATUS,
                NotifyHelper.foregroundNotification(this, getString(R.string.notif_starting))
        );
        // Safety cap; BookingActivity.onDestroy also stopService().
        handler.removeCallbacks(autoStop);
        handler.postDelayed(autoStop, 15 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoStop);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
