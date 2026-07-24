package com.selecttime.hogye;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class BookingActivity extends AppCompatActivity implements AucWebAutomator.Listener {
    public static final String EXTRA_USE_DATE = "use_date";
    public static final String EXTRA_PREFERRED_TIMES = "preferred_times";
    public static final String EXTRA_PREFERRED_COURTS = "preferred_courts";
    public static final String EXTRA_AUTO_TO_PAYMENT = "auto_to_payment";
    public static final String EXTRA_AUTO_CLICK_PAY = "auto_click_pay";
    public static final String EXTRA_QUICK_MODE = "quick_mode";
    public static final String EXTRA_OPEN_MODE = "open_mode";
    public static final String EXTRA_OPEN_AT_MS = "open_at_ms";
    public static final String EXTRA_FROM_ALARM = "from_alarm";
    /** True when launched from independent "오픈 예약 미리 테스트" (no production store writes). */
    public static final String EXTRA_IS_TEST = "is_test_open";

    public static final String MODE_NORMAL = "normal";
    public static final String MODE_WARM = "warm";
    public static final String MODE_STRIKE = "strike";

    private TextView statusView;
    private AucWebAutomator automator;
    private String openMode = MODE_NORMAL;
    private long openAtMs;
    private boolean fromAlarm;
    private boolean isTestOpen;

    private String overrideUseDate;
    private String overridePreferredTimes;
    private String overridePreferredCourts;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_booking);
        statusView = findViewById(R.id.bookingStatus);

        clearNotifications();
        applyIntentOverrides(getIntent());
        onAlarmLaunchIfNeeded();

        WebView webView = findViewById(R.id.webView);
        automator = new AucWebAutomator(this, webView, this);
        if (overrideUseDate != null || overridePreferredTimes != null || overridePreferredCourts != null) {
            automator.setOverrides(overrideUseDate, overridePreferredTimes, overridePreferredCourts);
        }
        startForMode();
    }

    private void startForMode() {
        if (MODE_WARM.equals(openMode)) {
            automator.ensureWarm(openAtMs);
        } else if (MODE_STRIKE.equals(openMode)) {
            chainNextOpenAfterStrike();
            automator.startStrike(openAtMs);
        } else {
            automator.start();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        clearNotifications();
        applyIntentOverrides(intent);
        onAlarmLaunchIfNeeded();
        if (automator == null) {
            return;
        }
        if (overrideUseDate != null || overridePreferredTimes != null || overridePreferredCourts != null) {
            automator.setOverrides(overrideUseDate, overridePreferredTimes, overridePreferredCourts);
        }
        if (MODE_STRIKE.equals(openMode)) {
            chainNextOpenAfterStrike();
            automator.beginStrikeNow();
        } else if (MODE_WARM.equals(openMode)) {
            // Prealert may already have started warm login — do not wipe progress.
            automator.ensureWarm(openAtMs);
        }
    }

    /** AlarmClock → Activity: start FGS + ringing notification. */
    private void onAlarmLaunchIfNeeded() {
        if (!MODE_WARM.equals(openMode) && !MODE_STRIKE.equals(openMode)) {
            return;
        }
        String prepared;
        if (isTestOpen) {
            // Do not write KEY_USE_DATE / schedule_next_* / open hour.
            prepared = OpenScheduleHelper.prepareTestOpenRun(this, overrideUseDate);
        } else {
            prepared = OpenScheduleHelper.prepareOpenRun(this);
        }
        if (prepared != null && !prepared.isEmpty()) {
            overrideUseDate = prepared;
        }
        String useDate = overrideUseDate != null ? overrideUseDate : prepared;

        Intent fg = new Intent(this, BookingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fg);
        } else {
            startService(fg);
        }

        if (fromAlarm) {
            Intent self = OpenAlarmScheduler.bookingIntent(
                    this, MODE_WARM.equals(openMode), openAtMs, useDate, isTestOpen);
            NotifyHelper.notifyOpenLaunch(this, self, MODE_WARM.equals(openMode), useDate);
        }
    }

    private void chainNextOpenAfterStrike() {
        if (isTestOpen) {
            // Test is one-shot; leave production "오픈 예약" schedule as-is.
            OpenAlarmScheduler.cancelTest(this);
            return;
        }
        SecureStore store = new SecureStore(this);
        if (!OpenScheduleHelper.isArmed(store)) {
            return;
        }
        long base = Math.max(System.currentTimeMillis(), openAtMs) + 1000L;
        OpenScheduleHelper.NextRun next = OpenScheduleHelper.computeNext(store, base);
        if (next != null) {
            OpenAlarmScheduler.scheduleNext(this, next);
        }
    }

    private void applyIntentOverrides(Intent intent) {
        if (intent == null) {
            return;
        }
        SecureStore store = new SecureStore(this);
        boolean quick = intent.getBooleanExtra(EXTRA_QUICK_MODE, false);
        isTestOpen = intent.getBooleanExtra(EXTRA_IS_TEST, false);
        fromAlarm = intent.getBooleanExtra(EXTRA_FROM_ALARM, false)
                || OpenAlarmScheduler.ACTION_WARM.equals(intent.getAction())
                || OpenAlarmScheduler.ACTION_PREALERT.equals(intent.getAction())
                || OpenAlarmScheduler.ACTION_OPEN.equals(intent.getAction());
        openMode = intent.getStringExtra(EXTRA_OPEN_MODE);
        if (openMode == null || openMode.isEmpty()) {
            if (OpenAlarmScheduler.ACTION_WARM.equals(intent.getAction())
                    || OpenAlarmScheduler.ACTION_PREALERT.equals(intent.getAction())) {
                openMode = MODE_WARM;
            } else if (OpenAlarmScheduler.ACTION_OPEN.equals(intent.getAction())) {
                openMode = MODE_STRIKE;
            } else {
                openMode = MODE_NORMAL;
            }
        }
        openAtMs = intent.getLongExtra(EXTRA_OPEN_AT_MS, 0L);

        if (intent.hasExtra(EXTRA_USE_DATE)) {
            overrideUseDate = intent.getStringExtra(EXTRA_USE_DATE);
        }
        if (intent.hasExtra(EXTRA_PREFERRED_TIMES)) {
            overridePreferredTimes = intent.getStringExtra(EXTRA_PREFERRED_TIMES);
        }
        if (intent.hasExtra(EXTRA_PREFERRED_COURTS)) {
            overridePreferredCourts = intent.getStringExtra(EXTRA_PREFERRED_COURTS);
        }
        // Production path may persist payment flags; test must not rewrite schedule settings.
        if (!isTestOpen) {
            if (intent.hasExtra(EXTRA_AUTO_TO_PAYMENT)) {
                store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT,
                        intent.getBooleanExtra(EXTRA_AUTO_TO_PAYMENT, true));
            }
            if (intent.hasExtra(EXTRA_AUTO_CLICK_PAY)) {
                store.putBool(SecureStore.KEY_AUTO_CLICK_PAY,
                        intent.getBooleanExtra(EXTRA_AUTO_CLICK_PAY, true));
            }
        }

        if (quick || MODE_WARM.equals(openMode) || MODE_STRIKE.equals(openMode)) {
            if (!isTestOpen) {
                store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT, true);
                store.putBool(SecureStore.KEY_AUTO_CLICK_PAY, true);
                store.putBool(SecureStore.KEY_AUTO_COURT, true);
            }

            String date = (overrideUseDate != null) ? overrideUseDate : store.get(SecureStore.KEY_USE_DATE, "");
            String time = (overridePreferredTimes != null) ? overridePreferredTimes : store.get(SecureStore.KEY_PREFERRED_TIMES, "");

            if (MODE_WARM.equals(openMode)) {
                statusView.setText(isTestOpen
                        ? ("[테스트] " + getString(R.string.warm_running, date))
                        : getString(R.string.warm_running, date));
            } else {
                statusView.setText(isTestOpen
                        ? ("[테스트] " + getString(R.string.quick_book_running, date, time))
                        : getString(R.string.quick_book_running, date, time));
            }
        }
    }

    @Override
    public void onStatus(String message) {
        onStatus(message, AucWebAutomator.TONE_NORMAL);
    }

    @Override
    public void onStatus(String message, int tone) {
        statusView.setText(message);
        switch (tone) {
            case AucWebAutomator.TONE_WAIT:
                // Last ~30s: amber/orange so "예약 대기" is unmistakable.
                statusView.setTextColor(getColor(R.color.warn_hot));
                statusView.setBackgroundColor(0x33FF8C42);
                statusView.setTextSize(17f);
                break;
            case AucWebAutomator.TONE_STRIKE:
                statusView.setTextColor(getColor(R.color.strike));
                statusView.setBackgroundColor(0x33FF5A5A);
                statusView.setTextSize(17f);
                break;
            case AucWebAutomator.TONE_WARN:
                statusView.setTextColor(getColor(R.color.warn));
                statusView.setBackgroundColor(0x33E8B84A);
                statusView.setTextSize(15f);
                break;
            default:
                statusView.setTextColor(getColor(R.color.accent));
                statusView.setBackgroundColor(getColor(R.color.accent_soft));
                statusView.setTextSize(15f);
                break;
        }
    }

    @Override
    public void onFinished(boolean success, String message) {
        statusView.setText(message);
        statusView.setTextColor(getColor(success ? R.color.accent : R.color.strike));
        statusView.setBackgroundColor(getColor(R.color.accent_soft));
        statusView.setTextSize(15f);
    }

    private void clearNotifications() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NotifyHelper.NOTIF_PREALERT);
            nm.cancel(NotifyHelper.NOTIF_OPEN_LAUNCH);
            // Optionally clear all if needed
            // nm.cancelAll();
        }
    }
}
