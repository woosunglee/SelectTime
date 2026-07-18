package com.selecttime.hogye;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class BookingWorker extends Worker {
    public BookingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        NotifyHelper.notify(ctx, NotifyHelper.NOTIF_STATUS,
                ctx.getString(R.string.app_name),
                ctx.getString(R.string.notif_starting));
        Intent fg = new Intent(ctx, BookingForegroundService.class);
        ctx.startForegroundService(fg);
        Intent ui = new Intent(ctx, BookingActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(ui);
        return Result.success();
    }
}
