package com.selecttime.hogye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Primary open-alarm path: AlarmClock fires this receiver, which starts
 * BookingActivity + FGS + full-screen notification (OEM-safe).
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
        boolean isTest = intent.getBooleanExtra(BookingActivity.EXTRA_IS_TEST, false);

        SecureStore store = new SecureStore(app);
        if (isTest) {
            if (!OpenScheduleHelper.isTestArmed(store)) {
                Log.w(TAG, "test alarm while test disarmed — ignore " + action);
                return;
            }
        } else if (!OpenScheduleHelper.isArmed(store)) {
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
        long openAt = intent.getLongExtra(BookingActivity.EXTRA_OPEN_AT_MS, 0L);

        if (isTest) {
            useDate = OpenScheduleHelper.prepareTestOpenRun(app, useDate);
            if (openAt <= 0) {
                openAt = OpenScheduleHelper.getTestOpenAt(store);
            }
        } else {
            if (useDate == null || useDate.isEmpty()) {
                useDate = OpenScheduleHelper.getNextUseDate(store);
            }
            if (openAt <= 0) {
                openAt = OpenScheduleHelper.getNextAt(store);
            }
            String prepared = OpenScheduleHelper.prepareOpenRun(app);
            if (prepared != null && !prepared.isEmpty()) {
                useDate = prepared;
            }
        }
        if (openAt <= 0) {
            openAt = System.currentTimeMillis();
        }

        Intent fg = new Intent(app, BookingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                app.startForegroundService(fg);
            } catch (Exception e) {
                Log.e(TAG, "startForegroundService failed", e);
            }
        } else {
            app.startService(fg);
        }

        boolean warmMode = prealert || warm;
        Intent ui = OpenAlarmScheduler.bookingIntent(app, warmMode, openAt, useDate, isTest);
        ui.setAction(action);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        if (prealert) {
            NotifyHelper.notifyPreOpenAlarm(app, useDate, openAt);
        }
        NotifyHelper.notifyOpenLaunch(app, ui, warmMode, useDate);

        if (isTest && open) {
            // One-shot: clear test state after strike fires (production schedule untouched).
            OpenAlarmScheduler.cancelTest(app);
        }

        try {
            app.startActivity(ui);
            Log.i(TAG, action + " startActivity BookingActivity ok use=" + useDate
                    + " warm=" + warmMode + " test=" + isTest);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed — rely on full-screen notification", e);
        }
    }
}
