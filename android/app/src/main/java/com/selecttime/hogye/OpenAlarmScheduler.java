package com.selecttime.hogye;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Open-time alarms (AlarmClock = exact + Doze-exempt + auto-start Activity):
 * <ul>
 *   <li>PREALERT (~4분 전): 소리/진동 알람 + 로그인 화면 자동 실행</li>
 *   <li>WARM (~3분 전): 로그인·달력 준비 재확인</li>
 *   <li>STRIKE (정각): 슬롯 클릭</li>
 * </ul>
 */
public final class OpenAlarmScheduler {
    private static final String TAG = "OpenAlarm";

    public static final String ACTION_PREALERT = "com.selecttime.hogye.action.OPEN_PREALERT";
    public static final String ACTION_WARM = "com.selecttime.hogye.action.OPEN_WARM";
    public static final String ACTION_OPEN = "com.selecttime.hogye.action.OPEN_STRIKE";

    public static final int REQ_PREALERT = 7100;
    public static final int REQ_WARM = 7101;
    public static final int REQ_OPEN = 7102;
    public static final int REQ_SHOW_PREALERT = 7110;
    public static final int REQ_SHOW_WARM = 7111;
    public static final int REQ_SHOW_OPEN = 7112;

    /** Loud heads-up alarm + start warm login early. */
    public static final long PREALERT_LEAD_MS = 4 * 60 * 1000L;
    /** Ensure login/calendar is ready this many ms before open. */
    public static final long WARM_LEAD_MS = 3 * 60 * 1000L;

    private OpenAlarmScheduler() {
    }

    public static boolean canExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void scheduleNext(Context context, OpenScheduleHelper.NextRun next) {
        if (next == null) {
            Log.w(TAG, "scheduleNext skipped — next is null");
            return;
        }
        Context app = context.getApplicationContext();
        SecureStore store = new SecureStore(app);
        OpenScheduleHelper.saveNext(store, next);

        long now = System.currentTimeMillis();
        long openAt = next.openAtMillis;

        long preAt = openAt - PREALERT_LEAD_MS;
        long warmAt = openAt - WARM_LEAD_MS;

        // PREALERT also launches BookingActivity(warm) so login starts immediately
        // when the user hears the alarm — not just MainActivity.
        if (preAt > now + 2000L && preAt < warmAt - 15_000L) {
            setAlarmClock(app, preAt, REQ_PREALERT, REQ_SHOW_PREALERT,
                    ACTION_PREALERT, openAt, next.useDate, true);
        } else {
            cancelReq(app, REQ_PREALERT, ACTION_PREALERT);
        }

        if (warmAt < now + 1500L) {
            warmAt = now + 1500L;
        }
        if (warmAt >= openAt - 500L) {
            cancelReq(app, REQ_WARM, ACTION_WARM);
        } else {
            // Activity PendingIntent → OS launches BookingActivity when alarm fires
            setAlarmClock(app, warmAt, REQ_WARM, REQ_SHOW_WARM,
                    ACTION_WARM, openAt, next.useDate, true);
        }
        setAlarmClock(app, Math.max(now + 1000L, openAt), REQ_OPEN, REQ_SHOW_OPEN,
                ACTION_OPEN, openAt, next.useDate, true);

        Log.i(TAG, "scheduled pre=" + preAt + " warm=" + warmAt + " open=" + openAt
                + " use=" + next.useDate + " exact=" + canExact(app));
    }

    public static void cancelAll(Context context) {
        Context app = context.getApplicationContext();
        cancelReq(app, REQ_PREALERT, ACTION_PREALERT);
        cancelReq(app, REQ_WARM, ACTION_WARM);
        cancelReq(app, REQ_OPEN, ACTION_OPEN);
    }

    public static void rescheduleIfArmed(Context context) {
        SecureStore store = new SecureStore(context);
        if (!OpenScheduleHelper.isArmed(store)) {
            cancelAll(context);
            return;
        }
        OpenScheduleHelper.NextRun next = OpenScheduleHelper.computeNext(store);
        if (next != null) {
            scheduleNext(context, next);
        } else {
            Log.w(TAG, "rescheduleIfArmed — no next run (check weekdays)");
        }
    }

    /**
     * @param launchBooking if true, AlarmClock operation opens BookingActivity directly;
     *                      if false, fires {@link OpenAlarmReceiver} for pre-alert only.
     */
    private static void setAlarmClock(Context context, long triggerAt, int reqCode, int showReq,
                                      String action, long openAt, String useDate,
                                      boolean launchBooking) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent op = launchBooking
                ? pendingBookingActivity(context, reqCode, action, openAt, useDate)
                : pendingBroadcast(context, reqCode, action, openAt, useDate);
        // Status-bar alarm clock tap should open the same booking flow, not MainActivity.
        Intent showIntent = launchBooking
                ? bookingIntent(context,
                ACTION_WARM.equals(action) || ACTION_PREALERT.equals(action),
                openAt, useDate)
                : new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (launchBooking) {
            showIntent.setAction(action);
        }
        PendingIntent show = PendingIntent.getActivity(
                context,
                showReq,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, show), op);
            Log.i(TAG, "setAlarmClock action=" + action + " at=" + triggerAt
                    + " launchBooking=" + launchBooking);
        } catch (SecurityException e) {
            Log.e(TAG, "setAlarmClock denied — fallback", e);
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op);
            } catch (Exception e2) {
                Log.e(TAG, "fallback alarm failed", e2);
            }
        }
    }

    private static void cancelReq(Context context, int reqCode, String action) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(pendingBroadcast(context, reqCode, action, 0, ""));
        am.cancel(pendingBookingActivity(context, reqCode, action, 0, ""));
    }

    private static PendingIntent pendingBroadcast(Context context, int reqCode, String action,
                                                  long openAt, String useDate) {
        Intent i = new Intent(context, OpenAlarmReceiver.class);
        i.setAction(action);
        i.putExtra(BookingActivity.EXTRA_OPEN_AT_MS, openAt);
        i.putExtra(BookingActivity.EXTRA_USE_DATE, useDate == null ? "" : useDate);
        return PendingIntent.getBroadcast(
                context,
                reqCode,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent pendingBookingActivity(Context context, int reqCode, String action,
                                                        long openAt, String useDate) {
        boolean warm = ACTION_WARM.equals(action) || ACTION_PREALERT.equals(action);
        Intent ui = bookingIntent(context, warm, openAt, useDate);
        // Distinct action so PendingIntents for warm vs strike do not collapse incorrectly
        ui.setAction(action);
        return PendingIntent.getActivity(
                context,
                reqCode,
                ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /** Build BookingActivity intent for warm/strike (shared by receiver + catch-up). */
    public static Intent bookingIntent(Context context, boolean warm, long openAt, String useDate) {
        SecureStore store = new SecureStore(context);
        Intent ui = new Intent(context, BookingActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ui.putExtra(BookingActivity.EXTRA_USE_DATE, useDate == null ? "" : useDate);
        ui.putExtra(BookingActivity.EXTRA_PREFERRED_TIMES,
                store.get(SecureStore.KEY_PREFERRED_TIMES, ""));
        ui.putExtra(BookingActivity.EXTRA_PREFERRED_COURTS,
                store.get(SecureStore.KEY_PREFERRED_COURTS, ""));
        ui.putExtra(BookingActivity.EXTRA_AUTO_TO_PAYMENT, true);
        ui.putExtra(BookingActivity.EXTRA_AUTO_CLICK_PAY, true);
        ui.putExtra(BookingActivity.EXTRA_QUICK_MODE, true);
        ui.putExtra(BookingActivity.EXTRA_OPEN_AT_MS, openAt);
        ui.putExtra(BookingActivity.EXTRA_OPEN_MODE,
                warm ? BookingActivity.MODE_WARM : BookingActivity.MODE_STRIKE);
        ui.putExtra(BookingActivity.EXTRA_FROM_ALARM, true);
        return ui;
    }
}
