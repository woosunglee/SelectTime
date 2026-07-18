package com.selecttime.hogye;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public final class NotifyHelper {
    public static final String CHANNEL_ID = "selecttime_booking";
    public static final int NOTIF_STATUS = 1001;
    public static final int NOTIF_AUTH = 1002;

    private NotifyHelper() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.notif_channel_desc));
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    public static Notification foregroundNotification(Context context, String text) {
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public static void notify(Context context, int id, String title, String body) {
        ensureChannel(context);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(id, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS may be denied
        }
    }
}
