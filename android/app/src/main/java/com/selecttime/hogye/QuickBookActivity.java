package com.selecttime.hogye;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pick a specific Hogye date/slot, then run auto login → slot → payment.
 */
public class QuickBookActivity extends AppCompatActivity {
    private EditText dateInput;
    private EditText timeInput;
    private EditText courtInput;
    private TextView todayNowText;
    private SecureStore store;

    private int year;
    private int month;
    private int day;
    private HogyeTimeSlots.Slot selectedSlot;
    private List<HogyeTimeSlots.Slot> currentSlots;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            refreshTodayNow();
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_book);
        store = new SecureStore(this);

        dateInput = findViewById(R.id.inputQuickDate);
        timeInput = findViewById(R.id.inputQuickTime);
        courtInput = findViewById(R.id.inputQuickCourt);
        todayNowText = findViewById(R.id.textQuickTodayNow);
        Button start = findViewById(R.id.btnQuickStart);

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        year = cal.get(Calendar.YEAR);
        month = cal.get(Calendar.MONTH);
        day = cal.get(Calendar.DAY_OF_MONTH);

        refreshSlotsForSelectedDate();
        // Default to 2타임
        selectedSlot = pickDefaultSlot();
        refreshDateText();
        refreshTimeText();

        String savedCourt = store.get(SecureStore.KEY_PREFERRED_COURTS, "9코트,10코트");
        courtInput.setText(savedCourt);
        courtInput.setFocusable(false);
        courtInput.setClickable(true);

        dateInput.setOnClickListener(v -> showDatePicker());
        timeInput.setOnClickListener(v -> showSlotPicker());
        courtInput.setOnClickListener(v -> showCourtPicker());

        start.setOnClickListener(v -> startAutoBooking());
        refreshTodayNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
    }

    @Override
    protected void onPause() {
        clockHandler.removeCallbacks(clockTick);
        super.onPause();
    }

    private void refreshTodayNow() {
        if (todayNowText == null) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd (E) HH:mm:ss", Locale.KOREAN)
                .format(new Date());
        todayNowText.setText(getString(R.string.quick_use_date_live, stamp));
        // Keep 이용일 field showing tomorrow's calendar date unless user changed it earlier.
        if (dateInput != null) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            String tomorrow = String.format(Locale.US, "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
            String cur = dateInput.getText() != null ? dateInput.getText().toString().trim() : "";
            // Only auto-sync when field still matches the initially opened 'tomorrow' selection.
            if (cur.isEmpty() || (year == cal.get(Calendar.YEAR)
                    && month == cal.get(Calendar.MONTH)
                    && day == cal.get(Calendar.DAY_OF_MONTH))) {
                year = cal.get(Calendar.YEAR);
                month = cal.get(Calendar.MONTH);
                day = cal.get(Calendar.DAY_OF_MONTH);
                if (!tomorrow.equals(cur)) {
                    dateInput.setText(tomorrow);
                }
            }
        }
    }

    private void refreshDateText() {
        dateInput.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day));
        refreshTodayNow();
    }

    private HogyeTimeSlots.Slot pickDefaultSlot() {
        // Prefer 2타임 (weekday 10:40 / Sunday 10:30)
        for (HogyeTimeSlots.Slot s : currentSlots) {
            if (s.label != null && s.label.contains("2타임")) {
                return s;
            }
        }
        for (HogyeTimeSlots.Slot s : currentSlots) {
            if ("10:40".equals(s.startTime) || "10:30".equals(s.startTime)) {
                return s;
            }
        }
        if (currentSlots.size() > 1) {
            return currentSlots.get(1);
        }
        return currentSlots.get(0);
    }

    private void refreshSlotsForSelectedDate() {
        currentSlots = HogyeTimeSlots.forDate(year, month, day);
        if (selectedSlot != null) {
            int idx = HogyeTimeSlots.indexOfStart(currentSlots, selectedSlot.startTime);
            selectedSlot = currentSlots.get(idx);
        }
    }

    private void showDatePicker() {
        ContextThemeWrapper themed = new ContextThemeWrapper(this, R.style.Theme_SelectTime_DatePicker);
        DatePicker picker = new DatePicker(themed);
        picker.init(year, month, day, null);

        // Keep calendar scrollable so 저장/취소 stay on screen on small phones.
        ScrollView scroll = new ScrollView(themed);
        scroll.setFillViewport(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scroll.addView(picker, lp);

        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        FrameLayout wrap = new FrameLayout(themed);
        wrap.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_SelectTime_DatePicker)
                .setTitle(R.string.label_pick_date)
                .setView(wrap)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    year = picker.getYear();
                    month = picker.getMonth();
                    day = picker.getDayOfMonth();
                    refreshSlotsForSelectedDate();
                    if (selectedSlot == null
                            || HogyeTimeSlots.indexOfStart(currentSlots, selectedSlot.startTime) < 0) {
                        selectedSlot = currentSlots.get(0);
                    } else {
                        selectedSlot = currentSlots.get(
                                HogyeTimeSlots.indexOfStart(currentSlots, selectedSlot.startTime));
                    }
                    refreshDateText();
                    refreshTimeText();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();

        int accent = ContextCompat.getColor(this, R.color.accent);
        Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (pos != null) {
            pos.setTextColor(accent);
        }
        if (neg != null) {
            neg.setTextColor(accent);
        }
    }

    private void showSlotPicker() {
        refreshSlotsForSelectedDate();
        int checked = HogyeTimeSlots.indexOfStart(
                currentSlots, selectedSlot != null ? selectedSlot.startTime : null);
        CharSequence[] labels = HogyeTimeSlots.labels(currentSlots);

        String title = isSunday()
                ? getString(R.string.hogye_slots_sunday)
                : getString(R.string.hogye_slots_weekday);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedSlot = currentSlots.get(which);
                    refreshTimeText();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCourtPicker() {
        CharSequence[] labels = HogyeTimeSlots.courtLabels();
        boolean[] checked = HogyeTimeSlots.courtsCheckedFromCsv(
                courtInput.getText() != null ? courtInput.getText().toString() : "");
        boolean any = false;
        for (boolean b : checked) {
            if (b) {
                any = true;
                break;
            }
        }
        if (!any) {
            checked[8] = true;
            checked[9] = true;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.label_courts)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String csv = HogyeTimeSlots.courtsCsvFromChecked(checked);
                    if (csv.isEmpty()) {
                        Toast.makeText(this, R.string.hint_pick_courts, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    courtInput.setText(csv);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean isSunday() {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month, day);
        return cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    private void refreshTimeText() {
        if (selectedSlot == null) {
            timeInput.setText("");
            return;
        }
        timeInput.setText(selectedSlot.label);
    }

    private void startAutoBooking() {
        String id = store.get(SecureStore.KEY_AUC_ID, "").trim();
        String pw = store.get(SecureStore.KEY_AUC_PASSWORD, "");
        if (id.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, R.string.quick_book_need_login, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        String date = dateInput.getText().toString().trim();
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Toast.makeText(this, R.string.quick_book_bad_date, Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedSlot == null) {
            Toast.makeText(this, R.string.quick_book_bad_time, Toast.LENGTH_SHORT).show();
            return;
        }

        // Prefer matching start time on the AUC calendar (e.g. 18:50), also keep range.
        String timeMatch = selectedSlot.startTime + "," + selectedSlot.range;
        String court = courtInput.getText().toString().trim();

        Intent intent = new Intent(this, BookingActivity.class);
        intent.putExtra(BookingActivity.EXTRA_USE_DATE, date);
        intent.putExtra(BookingActivity.EXTRA_PREFERRED_TIMES, timeMatch);
        intent.putExtra(BookingActivity.EXTRA_PREFERRED_COURTS, court);
        intent.putExtra(BookingActivity.EXTRA_AUTO_TO_PAYMENT, true);
        intent.putExtra(BookingActivity.EXTRA_AUTO_CLICK_PAY, true);
        intent.putExtra(BookingActivity.EXTRA_QUICK_MODE, true);
        startActivity(intent);
    }
}
