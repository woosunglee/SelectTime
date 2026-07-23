package com.selecttime.hogye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Backup path for open alarms. Primary warm/strike/prealert usually arrives via
 * AlarmClock → BookingActivity directly.
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

        // PREALERT and WARM both open BookingActivity in warm mode so login starts.
        boolean warmMode = prealert || warm;
        Intent ui = OpenAlarmScheduler.bookingIntent(app, warmMode, openAt, useDate);
        ui.setAction(action);

        if (prealert) {
            NotifyHelper.notifyPreOpenAlarm(app, useDate, openAt);
        }
        NotifyHelper.notifyOpenLaunch(app, ui, warmMode, useDate);

        try {
            app.startActivity(ui);
            Log.i(TAG, action + " startActivity BookingActivity ok use=" + useDate);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed — rely on full-screen notification", e);
        }
    }
}
