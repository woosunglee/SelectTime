package com.selecttime.hogye;

import android.annotation.SuppressLint;
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

    public static final String MODE_NORMAL = "normal";
    public static final String MODE_WARM = "warm";
    public static final String MODE_STRIKE = "strike";

    private TextView statusView;
    private AucWebAutomator automator;
    private String openMode = MODE_NORMAL;
    private long openAtMs;
    private boolean fromAlarm;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_booking);
        statusView = findViewById(R.id.bookingStatus);

        applyIntentOverrides(getIntent());
        onAlarmLaunchIfNeeded();

        WebView webView = findViewById(R.id.webView);
        automator = new AucWebAutomator(this, webView, this);
        startForMode();
    }

    private void startForMode() {
        if (MODE_WARM.equals(openMode)) {
            automator.startWarm(openAtMs);
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
        applyIntentOverrides(intent);
        onAlarmLaunchIfNeeded();
        if (automator == null) {
            return;
        }
        if (MODE_STRIKE.equals(openMode)) {
            chainNextOpenAfterStrike();
            automator.beginStrikeNow();
        } else if (MODE_WARM.equals(openMode)) {
            automator.startWarm(openAtMs);
        }
    }

    /** AlarmClock → Activity: start FGS + ringing notification. */
    private void onAlarmLaunchIfNeeded() {
        if (!MODE_WARM.equals(openMode) && !MODE_STRIKE.equals(openMode)) {
            return;
        }
        String useDate = OpenScheduleHelper.prepareOpenRun(this);
        SecureStore store = new SecureStore(this);
        String saved = store.get(SecureStore.KEY_USE_DATE, useDate);
        if (saved != null && !saved.isEmpty()) {
            useDate = saved;
        }

        Intent fg = new Intent(this, BookingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fg);
        } else {
            startService(fg);
        }

        if (fromAlarm) {
            Intent self = OpenAlarmScheduler.bookingIntent(
                    this, MODE_WARM.equals(openMode), openAtMs, useDate);
            NotifyHelper.notifyOpenLaunch(this, self, MODE_WARM.equals(openMode), useDate);
        }
    }

    private void chainNextOpenAfterStrike() {
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
        fromAlarm = intent.getBooleanExtra(EXTRA_FROM_ALARM, false)
                || OpenAlarmScheduler.ACTION_WARM.equals(intent.getAction())
                || OpenAlarmScheduler.ACTION_OPEN.equals(intent.getAction());
        openMode = intent.getStringExtra(EXTRA_OPEN_MODE);
        if (openMode == null || openMode.isEmpty()) {
            if (OpenAlarmScheduler.ACTION_WARM.equals(intent.getAction())) {
                openMode = MODE_WARM;
            } else if (OpenAlarmScheduler.ACTION_OPEN.equals(intent.getAction())) {
                openMode = MODE_STRIKE;
            } else {
                openMode = MODE_NORMAL;
            }
        }
        openAtMs = intent.getLongExtra(EXTRA_OPEN_AT_MS, 0L);

        if (intent.hasExtra(EXTRA_USE_DATE)) {
            store.put(SecureStore.KEY_USE_DATE, intent.getStringExtra(EXTRA_USE_DATE));
        }
        if (intent.hasExtra(EXTRA_PREFERRED_TIMES)) {
            store.put(SecureStore.KEY_PREFERRED_TIMES, intent.getStringExtra(EXTRA_PREFERRED_TIMES));
        }
        if (intent.hasExtra(EXTRA_PREFERRED_COURTS)) {
            store.put(SecureStore.KEY_PREFERRED_COURTS, intent.getStringExtra(EXTRA_PREFERRED_COURTS));
        }
        if (intent.hasExtra(EXTRA_AUTO_TO_PAYMENT)) {
            store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT,
                    intent.getBooleanExtra(EXTRA_AUTO_TO_PAYMENT, true));
        }
        if (intent.hasExtra(EXTRA_AUTO_CLICK_PAY)) {
            store.putBool(SecureStore.KEY_AUTO_CLICK_PAY,
                    intent.getBooleanExtra(EXTRA_AUTO_CLICK_PAY, true));
        }

        if (quick || MODE_WARM.equals(openMode) || MODE_STRIKE.equals(openMode)) {
            store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT, true);
            store.putBool(SecureStore.KEY_AUTO_CLICK_PAY, true);
            store.putBool(SecureStore.KEY_AUTO_COURT, true);
            String date = store.get(SecureStore.KEY_USE_DATE, "");
            String time = store.get(SecureStore.KEY_PREFERRED_TIMES, "");
            if (MODE_WARM.equals(openMode)) {
                statusView.setText(getString(R.string.warm_running, date));
            } else {
                statusView.setText(getString(R.string.quick_book_running, date, time));
            }
        }
    }

    @Override
    public void onStatus(String message) {
        statusView.setText(message);
    }

    @Override
    public void onFinished(boolean success, String message) {
        statusView.setText(message);
    }
}
