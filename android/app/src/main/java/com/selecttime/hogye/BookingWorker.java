package com.selecttime.hogye;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Legacy / backup entry. Prefer {@link OpenAlarmReceiver} for exact open timing.
 * Still launches strike-mode booking if enqueued.
 */
public class BookingWorker extends Worker {
    public BookingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SecureStore store = new SecureStore(ctx);

        if (!OpenScheduleHelper.isArmed(store)) {
            NotifyHelper.notify(ctx, NotifyHelper.NOTIF_STATUS,
                    ctx.getString(R.string.app_name),
                    ctx.getString(R.string.schedule_not_running));
            return Result.success();
        }

        String useDate = OpenScheduleHelper.prepareOpenRun(ctx);
        long openAt = OpenScheduleHelper.getNextAt(store);
        if (openAt <= 0) {
            openAt = System.currentTimeMillis();
        }

        NotifyHelper.notify(ctx, NotifyHelper.NOTIF_STATUS,
                ctx.getString(R.string.app_name),
                ctx.getString(R.string.notif_starting) + " (" + useDate + ")");

        Intent fg = new Intent(ctx, BookingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(fg);
        } else {
            ctx.startService(fg);
        }

        Intent ui = OpenAlarmScheduler.bookingIntent(ctx, false, openAt, useDate);
        ctx.startActivity(ui);
        // Next schedule is chained when BookingActivity strike actually starts.
        return Result.success();
    }
}
