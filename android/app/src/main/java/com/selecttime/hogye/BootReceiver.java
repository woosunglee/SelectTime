package com.selecttime.hogye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Re-arms exact open alarms after reboot or app update. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        Log.i(TAG, "reschedule after " + intent.getAction());
        OpenAlarmScheduler.rescheduleIfArmed(context.getApplicationContext());
    }
}
