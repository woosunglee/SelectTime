package com.selecttime.hogye;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private TextView statusView;
    private SecureStore store;

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

        statusView = findViewById(R.id.statusText);
        Button settings = findViewById(R.id.btnSettings);
        Button runNow = findViewById(R.id.btnRunNow);
        Button schedule = findViewById(R.id.btnSchedule);

        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        runNow.setOnClickListener(v -> {
            startActivity(new Intent(this, BookingActivity.class));
        });
        schedule.setOnClickListener(v -> scheduleOpen());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String id = store.get(SecureStore.KEY_AUC_ID, "");
        String method = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
        int hour = store.getInt(SecureStore.KEY_OPEN_HOUR, 15);
        int minute = store.getInt(SecureStore.KEY_OPEN_MINUTE, 0);
        statusView.setText(getString(
                R.string.main_status,
                id.isEmpty() ? "(미설정)" : id,
                method,
                String.format("%02d:%02d", hour, minute)
        ));
    }

    private void scheduleOpen() {
        int hour = store.getInt(SecureStore.KEY_OPEN_HOUR, 15);
        int minute = store.getInt(SecureStore.KEY_OPEN_MINUTE, 0);
        long delayMs = delayUntil(hour, minute);
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BookingWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(this).enqueueUniqueWork(
                "selecttime-open",
                ExistingWorkPolicy.REPLACE,
                req
        );
        Toast.makeText(this,
                getString(R.string.scheduled, delayMs / 1000),
                Toast.LENGTH_LONG).show();
        NotifyHelper.notify(this, NotifyHelper.NOTIF_STATUS,
                getString(R.string.app_name),
                getString(R.string.scheduled, delayMs / 1000));
    }

    private static long delayUntil(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        // lead 30 seconds
        target.add(Calendar.SECOND, -30);
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }
        return target.getTimeInMillis() - now.getTimeInMillis();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }
}
