package com.selecttime.hogye;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NotifyHelper {
    public static final String CHANNEL_ID = "selecttime_booking";
    public static final String CHANNEL_ALARM_ID = "selecttime_open_alarm";
    public static final int NOTIF_STATUS = 1001;
    public static final int NOTIF_AUTH = 1002;
    public static final int NOTIF_PREALERT = 1003;
    public static final int NOTIF_OPEN_LAUNCH = 1004;

    private NotifyHelper() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.notif_channel_desc));
        nm.createNotificationChannel(channel);

        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM_ID,
                context.getString(R.string.notif_alarm_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        alarm.setDescription(context.getString(R.string.notif_alarm_channel_desc));
        alarm.enableVibration(true);
        alarm.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        alarm.setBypassDnd(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        Uri sound = Settings.System.DEFAULT_ALARM_ALERT_URI != null
                ? Settings.System.DEFAULT_ALARM_ALERT_URI
                : Settings.System.DEFAULT_NOTIFICATION_URI;
        alarm.setSound(sound, aa);
        nm.createNotificationChannel(alarm);
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

    /** ~4분 전: 소리·진동 알람 + 예약(로그인) 화면 자동 실행. */
    public static void notifyPreOpenAlarm(Context context, String useDate, long openAtMs) {
        ensureChannel(context);
        Intent booking = OpenAlarmScheduler.bookingIntent(context, true, openAtMs, useDate);
        booking.setAction(OpenAlarmScheduler.ACTION_PREALERT);
        PendingIntent pi = PendingIntent.getActivity(
                context, 8200, booking,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String when = new SimpleDateFormat("HH:mm", Locale.KOREAN).format(new Date(openAtMs));
        String body = context.getString(R.string.notif_prealert_body, when, useDate);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.notif_prealert_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setTimeoutAfter(4 * 60 * 1000L);
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_PREALERT, b.build());
        } catch (SecurityException ignored) {
            // denied
        }
    }

    /**
     * High-priority / full-screen notification that opens BookingActivity.
     * Backup when background {@code startActivity} is blocked.
     */
    public static void notifyOpenLaunch(Context context, Intent bookingIntent,
                                        boolean warm, String useDate) {
        ensureChannel(context);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                warm ? 8101 : 8102,
                bookingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String title = warm
                ? context.getString(R.string.notif_warm_alarm_title)
                : context.getString(R.string.notif_strike_alarm_title);
        String body = warm
                ? context.getString(R.string.notif_warm_start, useDate)
                : context.getString(R.string.notif_starting) + " (" + useDate + ")";
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL);
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_OPEN_LAUNCH, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS / full-screen may be denied
        }
    }
}
