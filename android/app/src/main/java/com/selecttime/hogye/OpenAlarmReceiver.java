package com.selecttime.hogye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Handles open pre-alert (sound alarm) and backup warm/strike launches.
 * Primary warm/strike usually arrives via AlarmClock → BookingActivity directly.
 */
public class OpenAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "OpenAlarmRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        Context app = context.getApplicationContext();
        String action = intent.getAction();

        SecureStore store = new SecureStore(app);
        if (!OpenScheduleHelper.isArmed(store)) {
            Log.w(TAG, "alarm while disarmed — ignore " + action);
            return;
        }

        boolean prealert = OpenAlarmScheduler.ACTION_PREALERT.equals(action);
        boolean warm = OpenAlarmScheduler.ACTION_WARM.equals(action);
        boolean open = OpenAlarmScheduler.ACTION_OPEN.equals(action);
        if (!prealert && !warm && !open) {
            return;
        }

        String useDate = intent.getStringExtra(BookingActivity.EXTRA_USE_DATE);
        if (useDate == null || useDate.isEmpty()) {
            useDate = OpenScheduleHelper.getNextUseDate(store);
        }
        long openAt = intent.getLongExtra(BookingActivity.EXTRA_OPEN_AT_MS,
                OpenScheduleHelper.getNextAt(store));
        if (openAt <= 0) {
            openAt = System.currentTimeMillis();
        }

        if (prealert) {
            Log.i(TAG, "PREALERT use=" + useDate + " openAt=" + openAt);
            NotifyHelper.notifyPreOpenAlarm(app, useDate, openAt);
            // Bring app to foreground on main screen so user notices
            try {
                Intent main = new Intent(app, MainActivity.class);
                main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                app.startActivity(main);
            } catch (Exception e) {
                Log.w(TAG, "prealert start MainActivity failed", e);
            }
            return;
        }

        // Backup path if Activity PendingIntent was not used / failed
        String prepared = OpenScheduleHelper.prepareOpenRun(app);
        if (prepared != null && !prepared.isEmpty()) {
            useDate = prepared;
        }

        Intent fg = new Intent(app, BookingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(fg);
        } else {
            app.startService(fg);
        }

        Intent ui = OpenAlarmScheduler.bookingIntent(app, warm, openAt, useDate);
        NotifyHelper.notifyOpenLaunch(app, ui, warm, useDate);

        try {
            app.startActivity(ui);
            Log.i(TAG, (warm ? "WARM" : "OPEN") + " startActivity ok use=" + useDate);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed — rely on full-screen notification", e);
        }
    }
}
