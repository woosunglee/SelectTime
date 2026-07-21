package com.selecttime.hogye;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    private static final String[] DAY_KEYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
    private static final int[] DAY_IDS = {
            R.id.dayMon, R.id.dayTue, R.id.dayWed, R.id.dayThu,
            R.id.dayFri, R.id.daySat, R.id.daySun
    };

    private SecureStore store;
    private EditText aucId;
    private EditText aucPassword;
    private EditText useDate;
    private EditText partySize;
    private EditText discountSenior;
    private EditText discountMultiChild;
    private TextView todayNowText;
    private EditText preferredTimes;
    private EditText preferredCourts;
    private EditText paymentMethodInput;
    private EditText cardIssuerInput;
    private CheckBox autoCourt;
    private CheckBox autoToPayment;
    private CheckBox autoClickPay;
    private ImageButton togglePassword;
    private boolean passwordVisible;
    private final TextView[] dayChips = new TextView[7];
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            refreshTodayNow();
            clockHandler.postDelayed(this, 1000);
        }
    };

    /** Stored value for automation (e.g. 10:40,10:40~12:10). */
    private String preferredTimesValue = "10:40,10:40~12:10";
    private String preferredCourtsValue = "9코트,10코트";
    private String paymentMethodValue = "card";
    private String cardIssuerValue = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new SecureStore(this);

        aucId = findViewById(R.id.inputAucId);
        aucPassword = findViewById(R.id.inputAucPassword);
        useDate = findViewById(R.id.inputUseDate);
        partySize = findViewById(R.id.inputPartySize);
        discountSenior = findViewById(R.id.inputDiscountSenior);
        discountMultiChild = findViewById(R.id.inputDiscountMultiChild);
        todayNowText = findViewById(R.id.textTodayNow);
        preferredTimes = findViewById(R.id.inputPreferredTimes);
        preferredCourts = findViewById(R.id.inputPreferredCourts);
        paymentMethodInput = findViewById(R.id.inputPaymentMethod);
        cardIssuerInput = findViewById(R.id.inputCardIssuer);
        autoCourt = findViewById(R.id.checkAutoCourt);
        autoToPayment = findViewById(R.id.checkAutoToPayment);
        autoClickPay = findViewById(R.id.checkAutoClickPay);
        togglePassword = findViewById(R.id.btnTogglePassword);
        Button save = findViewById(R.id.btnSave);

        preferredTimes.setFocusable(false);
        preferredTimes.setClickable(true);
        preferredTimes.setOnClickListener(v -> showTimeSlotPicker());
        preferredCourts.setFocusable(false);
        preferredCourts.setClickable(true);
        preferredCourts.setOnClickListener(v -> showCourtPicker());

        paymentMethodInput.setFocusable(false);
        paymentMethodInput.setClickable(true);
        paymentMethodInput.setOnClickListener(v -> showPaymentMethodPicker());
        cardIssuerInput.setFocusable(false);
        cardIssuerInput.setClickable(true);
        cardIssuerInput.setOnClickListener(v -> showCardIssuerPicker());

        for (int i = 0; i < DAY_IDS.length; i++) {
            dayChips[i] = findViewById(DAY_IDS[i]);
            dayChips[i].setOnClickListener(v -> {
                v.setSelected(!v.isSelected());
                updateDayChipColor((TextView) v);
            });
        }

        togglePassword.setOnClickListener(v -> togglePasswordVisibility());

        load();
        refreshTodayNow();
        save.setOnClickListener(v -> {
            persist();
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
        });
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
        todayNowText.setText(getString(R.string.today_now_format, stamp));
        syncUseDateToToday();
    }

    private void syncUseDateToToday() {
        if (useDate == null) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        String today = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
        String cur = useDate.getText() != null ? useDate.getText().toString().trim() : "";
        if (!today.equals(cur)) {
            useDate.setText(today);
        }
    }

    private void togglePasswordVisibility() {
        int start = aucPassword.getSelectionStart();
        int end = aucPassword.getSelectionEnd();
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            aucPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            togglePassword.setImageResource(R.drawable.ic_visibility);
        } else {
            aucPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            togglePassword.setImageResource(R.drawable.ic_visibility_off);
        }
        aucPassword.setSelection(Math.max(start, 0), Math.max(end, 0));
    }

    private void load() {
        aucId.setText(store.get(SecureStore.KEY_AUC_ID, ""));
        aucPassword.setText(store.get(SecureStore.KEY_AUC_PASSWORD, ""));
        syncUseDateToToday();

        partySize.setText(String.valueOf(store.getInt(SecureStore.KEY_PARTY_SIZE, SecureStore.DEFAULT_PARTY_SIZE)));
        discountSenior.setText(String.valueOf(store.getInt(SecureStore.KEY_DISCOUNT_SENIOR, SecureStore.DEFAULT_DISCOUNT_SENIOR)));
        discountMultiChild.setText(String.valueOf(store.getInt(SecureStore.KEY_DISCOUNT_MULTI_CHILD, SecureStore.DEFAULT_DISCOUNT_MULTI_CHILD)));

        preferredTimesValue = store.get(SecureStore.KEY_PREFERRED_TIMES, "10:40,10:40~12:10");
        preferredCourtsValue = store.get(SecureStore.KEY_PREFERRED_COURTS, "9코트,10코트");
        refreshTimesDisplay();
        refreshCourtsDisplay();
        autoCourt.setChecked(store.getBool(SecureStore.KEY_AUTO_COURT, true));
        autoToPayment.setChecked(store.getBool(SecureStore.KEY_AUTO_TO_PAYMENT, true));
        autoClickPay.setChecked(store.getBool(SecureStore.KEY_AUTO_CLICK_PAY, false));

        paymentMethodValue = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
        refreshPaymentMethodDisplay();
        cardIssuerValue = store.get(SecureStore.KEY_CARD_ISSUER, "").trim();
        refreshCardIssuerDisplay();

        Set<String> selected = new HashSet<>(Arrays.asList(
                store.get(SecureStore.KEY_WEEKDAYS, "mon,wed,fri").split(",")));
        for (int i = 0; i < DAY_KEYS.length; i++) {
            boolean on = selected.contains(DAY_KEYS[i]);
            dayChips[i].setSelected(on);
            updateDayChipColor(dayChips[i]);
        }
    }

    private void refreshTimesDisplay() {
        preferredTimes.setText(HogyeTimeSlots.displayPreferredTimes(
                preferredTimesValue, HogyeTimeSlots.weekdaySlots()));
    }

    private void refreshCourtsDisplay() {
        preferredCourts.setText(preferredCourtsValue);
    }

    private void refreshPaymentMethodDisplay() {
        paymentMethodInput.setText("app_card".equals(paymentMethodValue) ? "앱카드" : "신용카드");
    }

    private void refreshCardIssuerDisplay() {
        if (cardIssuerValue == null || cardIssuerValue.isEmpty()) {
            cardIssuerInput.setText("(선택 안 함)");
        } else {
            cardIssuerInput.setText(cardIssuerValue);
        }
    }

    private void showPaymentMethodPicker() {
        String[] labels = getResources().getStringArray(R.array.payment_methods);
        int checked = "app_card".equals(paymentMethodValue) ? 1 : 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.dialog_single_choice, android.R.id.text1, labels);
        new AlertDialog.Builder(this, R.style.Theme_SelectTime_ListDialog)
                .setTitle(R.string.label_payment_method)
                .setSingleChoiceItems(adapter, checked, (d, which) -> {
                    paymentMethodValue = which == 1 ? "app_card" : "card";
                    refreshPaymentMethodDisplay();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCardIssuerPicker() {
        String[] labels = getResources().getStringArray(R.array.card_issuers);
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            if (cardIssuerValue.isEmpty() && "(선택 안 함)".equals(label)) {
                checked = i;
                break;
            }
            if (cardIssuerValue.equals(label)) {
                checked = i;
                break;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.dialog_single_choice, android.R.id.text1, labels);
        new AlertDialog.Builder(this, R.style.Theme_SelectTime_ListDialog)
                .setTitle(R.string.label_card_issuer)
                .setSingleChoiceItems(adapter, checked, (d, which) -> {
                    String picked = labels[which];
                    cardIssuerValue = "(선택 안 함)".equals(picked) ? "" : picked;
                    refreshCardIssuerDisplay();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTimeSlotPicker() {
        List<HogyeTimeSlots.Slot> catalog = HogyeTimeSlots.weekdaySlots();
        CharSequence[] labels = HogyeTimeSlots.labels(catalog);
        boolean[] checked = new boolean[catalog.size()];
        List<HogyeTimeSlots.Slot> matched =
                HogyeTimeSlots.matchPreferredFromStored(preferredTimesValue, catalog);
        for (HogyeTimeSlots.Slot s : matched) {
            int idx = HogyeTimeSlots.indexOfStart(catalog, s.startTime);
            if (idx >= 0 && idx < checked.length) {
                checked[idx] = true;
            }
        }
        if (matched.isEmpty() && catalog.size() > 1) {
            checked[1] = true;
        }

        new AlertDialog.Builder(this, R.style.Theme_SelectTime_ListDialog)
                .setTitle(R.string.hogye_slots_weekday)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    List<HogyeTimeSlots.Slot> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            picked.add(catalog.get(i));
                        }
                    }
                    if (picked.isEmpty()) {
                        Toast.makeText(this, R.string.quick_book_bad_time, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    preferredTimesValue = HogyeTimeSlots.toPreferredTimesValue(picked);
                    refreshTimesDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCourtPicker() {
        String[] labels = new String[12];
        boolean[] checked = new boolean[12];
        Set<String> selected = new HashSet<>();
        if (preferredCourtsValue != null) {
            for (String part : preferredCourtsValue.split(",")) {
                selected.add(part.trim());
            }
        }
        for (int i = 0; i < 12; i++) {
            labels[i] = (i + 1) + "코트";
            checked[i] = selected.contains(labels[i]);
        }
        new AlertDialog.Builder(this, R.style.Theme_SelectTime_ListDialog)
                .setTitle(R.string.hint_pick_courts)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (!hasAny(checked)) {
                        Toast.makeText(this, R.string.hint_pick_courts, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            picked.add(labels[i]);
                        }
                    }
                    preferredCourtsValue = String.join(",", picked);
                    refreshCourtsDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean hasAny(boolean[] checked) {
        for (boolean b : checked) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    private void persist() {
        store.put(SecureStore.KEY_AUC_ID, aucId.getText().toString().trim());
        store.put(SecureStore.KEY_AUC_PASSWORD, aucPassword.getText().toString());
        store.put(SecureStore.KEY_USE_DATE, useDate.getText().toString().trim());

        store.putInt(SecureStore.KEY_PARTY_SIZE, getInt(partySize, SecureStore.DEFAULT_PARTY_SIZE));
        store.putInt(SecureStore.KEY_DISCOUNT_SENIOR, getInt(discountSenior, SecureStore.DEFAULT_DISCOUNT_SENIOR));
        store.putInt(SecureStore.KEY_DISCOUNT_MULTI_CHILD, getInt(discountMultiChild, SecureStore.DEFAULT_DISCOUNT_MULTI_CHILD));

        store.put(SecureStore.KEY_PREFERRED_TIMES, preferredTimesValue);
        store.put(SecureStore.KEY_PREFERRED_COURTS, preferredCourtsValue);
        store.putBool(SecureStore.KEY_AUTO_COURT, autoCourt.isChecked());
        store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT, autoToPayment.isChecked());
        store.putBool(SecureStore.KEY_AUTO_CLICK_PAY, autoClickPay.isChecked());
        store.put(SecureStore.KEY_PAYMENT_METHOD, paymentMethodValue);
        store.put(SecureStore.KEY_CARD_ISSUER, cardIssuerValue == null ? "" : cardIssuerValue);
        store.put(SecureStore.KEY_WEEKDAYS, selectedWeekdaysCsv());
        store.putInt(SecureStore.KEY_OPEN_HOUR, 15);
        store.putInt(SecureStore.KEY_OPEN_MINUTE, 0);
        store.putInt(SecureStore.KEY_DAYS_AHEAD, 7);
    }

    private String selectedWeekdaysCsv() {
        List<String> days = new ArrayList<>();
        for (int i = 0; i < DAY_KEYS.length; i++) {
            if (dayChips[i].isSelected()) {
                days.add(DAY_KEYS[i]);
            }
        }
        return String.join(",", days);
    }

    private int getInt(EditText et, int def) {
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private void updateDayChipColor(TextView chip) {
        chip.setTextColor(getColor(chip.isSelected() ? R.color.accent : R.color.text));
    }
}
