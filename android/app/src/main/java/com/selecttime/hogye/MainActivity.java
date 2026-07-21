package com.selecttime.hogye;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;

import android.content.pm.PackageInfo;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final long MISS_GRACE_MS = 90_000L;
    private static final long CATCHUP_WINDOW_MS = 45 * 60 * 1000L;

    private TextView statusView;
    private TextView todayNowView;
    private TextView scheduleStatusView;
    private Button scheduleButton;
    private SecureStore store;
    private boolean catchUpLaunched;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            refreshTodayNow();
            refreshScheduleStatus();
            clockHandler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new SecureStore(this);
        NotifyHelper.ensureChannel(this);
        requestNotifPermission();

        logWebViewVersion();

        statusView = findViewById(R.id.statusText);
        todayNowView = findViewById(R.id.textTodayNow);
        scheduleStatusView = findViewById(R.id.textScheduleStatus);
        Button settings = findViewById(R.id.btnSettings);
        Button quickBook = findViewById(R.id.btnQuickBook);
        scheduleButton = findViewById(R.id.btnSchedule);

        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        quickBook.setOnClickListener(v ->
                startActivity(new Intent(this, QuickBookActivity.class)));
        scheduleButton.setOnClickListener(v -> toggleSchedule());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
    }

    @Override
    protected void onPause() {
        clockHandler.removeCallbacks(clockTick);
        super.onPause();
    }

    private void refreshTodayNow() {
        if (todayNowView == null) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd (E) HH:mm:ss", Locale.KOREAN)
                .format(new Date());
        todayNowView.setText(getString(R.string.today_now_format, stamp));
    }

    private void refreshStatus() {
        String id = store.get(SecureStore.KEY_AUC_ID, "");
        String method = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
        String methodLabel = "app_card".equals(method) ? "앱카드" : "신용카드";
        int hour = store.getInt(SecureStore.KEY_OPEN_HOUR, 15);
        int minute = store.getInt(SecureStore.KEY_OPEN_MINUTE, 0);
        String days = OpenScheduleHelper.preferredWeekdaysLabel(store);
        statusView.setText(getString(
                R.string.main_status,
                id.isEmpty() ? "(미설정)" : id,
                methodLabel,
                String.format(Locale.US, "%02d:%02d", hour, minute),
                days
        ));
        refreshTodayNow();
        refreshScheduleStatus();
    }

    private void refreshScheduleStatus() {
        if (scheduleStatusView == null) {
            return;
        }
        boolean armed = OpenScheduleHelper.isArmed(store);
        OpenScheduleHelper.NextRun preview = OpenScheduleHelper.computeNext(store);
        String preferred = OpenScheduleHelper.preferredWeekdaysLabel(store);

        if (armed) {
            long nextAt = OpenScheduleHelper.getNextAt(store);
            String useDate = OpenScheduleHelper.getNextUseDate(store);
            String when = nextAt > 0
                    ? new SimpleDateFormat("yyyy-MM-dd (E) HH:mm:ss", Locale.KOREAN)
                    .format(new Date(nextAt))
                    : "-";
            String useDow = preview != null && useDate.equals(preview.useDate)
                    ? preview.useWeekdayLabel
                    : "";
            long now = System.currentTimeMillis();
            boolean missed = nextAt > 0 && nextAt < now - MISS_GRACE_MS;
            if (missed) {
                scheduleStatusView.setTextColor(getColor(R.color.warn));
                scheduleStatusView.setText(getString(R.string.schedule_missed, when, useDate));
                handleMissedOpen(nextAt, useDate);
            } else {
                scheduleStatusView.setTextColor(getColor(R.color.accent));
                scheduleStatusView.setText(getString(
                        R.string.schedule_waiting,
                        preferred,
                        when,
                        useDate,
                        useDow.isEmpty() ? "-" : useDow
                ));
            }
            if (scheduleButton != null) {
                scheduleButton.setText(R.string.schedule_stop);
            }
        } else if (preview != null) {
            scheduleStatusView.setTextColor(getColor(R.color.text_muted));
            scheduleStatusView.setText(getString(
                    R.string.schedule_preview,
                    preferred,
                    preview.openLabel,
                    preview.useDate,
                    preview.useWeekdayLabel
            ));
            if (scheduleButton != null) {
                scheduleButton.setText(R.string.schedule_open);
            }
        } else {
            scheduleStatusView.setTextColor(getColor(R.color.warn));
            scheduleStatusView.setText(R.string.schedule_not_running);
            if (scheduleButton != null) {
                scheduleButton.setText(R.string.schedule_open);
            }
        }
    }

    private void handleMissedOpen(long missedAt, String useDate) {
        if (catchUpLaunched) {
            return;
        }
        catchUpLaunched = true;
        long late = System.currentTimeMillis() - missedAt;
        if (late > 0 && late <= CATCHUP_WINDOW_MS) {
            Toast.makeText(this, R.string.schedule_catchup_toast, Toast.LENGTH_LONG).show();
            String date = OpenScheduleHelper.prepareOpenRun(this);
            if (useDate != null && useDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                date = useDate;
                new SecureStore(this).put(SecureStore.KEY_USE_DATE, date);
            }
            startActivity(OpenAlarmScheduler.bookingIntent(this, false, missedAt, date));
        } else {
            Toast.makeText(this, R.string.schedule_missed_reschedule_toast, Toast.LENGTH_LONG).show();
        }
        OpenScheduleHelper.NextRun next = OpenScheduleHelper.computeNext(store);
        if (next != null) {
            OpenAlarmScheduler.scheduleNext(this, next);
        } else {
            OpenScheduleHelper.setArmed(store, false);
            OpenAlarmScheduler.cancelAll(this);
        }
    }

    private void toggleSchedule() {
        if (OpenScheduleHelper.isArmed(store)) {
            stopSchedule();
        } else {
            startSchedule();
        }
        refreshScheduleStatus();
    }

    private void startSchedule() {
        String id = store.get(SecureStore.KEY_AUC_ID, "").trim();
        String pw = store.get(SecureStore.KEY_AUC_PASSWORD, "");
        if (id.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, R.string.quick_book_need_login, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        if (!OpenScheduleHelper.hasPreferredWeekdays(store)) {
            Toast.makeText(this, R.string.schedule_need_weekdays, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        if (!OpenAlarmScheduler.canExact(this)) {
            ensureExactAlarmPermission();
            Toast.makeText(this, R.string.exact_alarm_required, Toast.LENGTH_LONG).show();
            return;
        }

        ensureBatteryOptimization();

        OpenScheduleHelper.NextRun next = OpenScheduleHelper.computeNext(store);
        if (next == null) {
            Toast.makeText(this, R.string.schedule_need_weekdays, Toast.LENGTH_LONG).show();
            return;
        }

        catchUpLaunched = false;
        OpenAlarmScheduler.scheduleNext(this, next);

        long warmAt = next.openAtMillis - OpenAlarmScheduler.WARM_LEAD_MS;
        String warmHint = warmAt > System.currentTimeMillis()
                ? new SimpleDateFormat("HH:mm", Locale.KOREAN).format(new Date(warmAt))
                : "곧";
        String msg = getString(R.string.schedule_armed_toast_warm,
                next.openLabel, next.useDate, next.useWeekdayLabel,
                next.preferredLabel, warmHint);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        NotifyHelper.notify(this, NotifyHelper.NOTIF_STATUS, getString(R.string.app_name), msg);
    }

    private void stopSchedule() {
        OpenScheduleHelper.setArmed(store, false);
        OpenAlarmScheduler.cancelAll(this);
        catchUpLaunched = false;
        Toast.makeText(this, R.string.schedule_stopped_toast, Toast.LENGTH_SHORT).show();
        NotifyHelper.notify(this, NotifyHelper.NOTIF_STATUS,
                getString(R.string.app_name),
                getString(R.string.schedule_not_running));
    }

    private void ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null && am.canScheduleExactAlarms()) {
            return;
        }
        Toast.makeText(this, R.string.exact_alarm_permission_hint, Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
            } catch (Exception ignored2) {
                // no settings activity
            }
        }
    }

    private void ensureBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        Toast.makeText(this, R.string.battery_opt_hint, Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored2) {
                // ignore
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void logWebViewVersion() {
        PackageInfo info = WebViewCompat.getCurrentWebViewPackage(this);
        if (info != null) {
            Log.i("SelectTime", "Current WebView Package: " + info.packageName + " version=" + info.versionName);
        }
    }
}
