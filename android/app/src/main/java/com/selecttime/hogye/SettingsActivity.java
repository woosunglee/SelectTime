package com.selecttime.hogye;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private SecureStore store;
    private EditText aucId;
    private EditText aucPassword;
    private EditText preferredTimes;
    private EditText daysAhead;
    private EditText openHour;
    private EditText openMinute;
    private EditText cardNumber;
    private EditText cardExpiry;
    private EditText cardCvc;
    private EditText cardPassword;
    private EditText cardBirth;
    private EditText reservationUrl;
    private Spinner paymentMethod;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new SecureStore(this);

        aucId = findViewById(R.id.inputAucId);
        aucPassword = findViewById(R.id.inputAucPassword);
        preferredTimes = findViewById(R.id.inputPreferredTimes);
        daysAhead = findViewById(R.id.inputDaysAhead);
        openHour = findViewById(R.id.inputOpenHour);
        openMinute = findViewById(R.id.inputOpenMinute);
        cardNumber = findViewById(R.id.inputCardNumber);
        cardExpiry = findViewById(R.id.inputCardExpiry);
        cardCvc = findViewById(R.id.inputCardCvc);
        cardPassword = findViewById(R.id.inputCardPassword);
        cardBirth = findViewById(R.id.inputCardBirth);
        reservationUrl = findViewById(R.id.inputReservationUrl);
        paymentMethod = findViewById(R.id.spinnerPayment);
        Button save = findViewById(R.id.btnSave);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.payment_methods, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        paymentMethod.setAdapter(adapter);

        load();
        save.setOnClickListener(v -> {
            persist();
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void load() {
        aucId.setText(store.get(SecureStore.KEY_AUC_ID, ""));
        aucPassword.setText(store.get(SecureStore.KEY_AUC_PASSWORD, ""));
        preferredTimes.setText(store.get(SecureStore.KEY_PREFERRED_TIMES, "19:00,20:00,18:00"));
        daysAhead.setText(String.valueOf(store.getInt(SecureStore.KEY_DAYS_AHEAD, 7)));
        openHour.setText(String.valueOf(store.getInt(SecureStore.KEY_OPEN_HOUR, 15)));
        openMinute.setText(String.valueOf(store.getInt(SecureStore.KEY_OPEN_MINUTE, 0)));
        cardNumber.setText(store.get(SecureStore.KEY_CARD_NUMBER, ""));
        cardExpiry.setText(store.get(SecureStore.KEY_CARD_EXPIRY, ""));
        cardCvc.setText(store.get(SecureStore.KEY_CARD_CVC, ""));
        cardPassword.setText(store.get(SecureStore.KEY_CARD_PASSWORD, ""));
        cardBirth.setText(store.get(SecureStore.KEY_CARD_BIRTH, ""));
        reservationUrl.setText(store.get(SecureStore.KEY_RESERVATION_URL, SecureStore.DEFAULT_URL));
        String method = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
        paymentMethod.setSelection("app_card".equals(method) ? 1 : 0);
    }

    private void persist() {
        store.put(SecureStore.KEY_AUC_ID, aucId.getText().toString().trim());
        store.put(SecureStore.KEY_AUC_PASSWORD, aucPassword.getText().toString());
        store.put(SecureStore.KEY_PREFERRED_TIMES, preferredTimes.getText().toString().trim());
        store.putInt(SecureStore.KEY_DAYS_AHEAD, parseInt(daysAhead.getText().toString(), 7));
        store.putInt(SecureStore.KEY_OPEN_HOUR, parseInt(openHour.getText().toString(), 15));
        store.putInt(SecureStore.KEY_OPEN_MINUTE, parseInt(openMinute.getText().toString(), 0));
        store.put(SecureStore.KEY_CARD_NUMBER, cardNumber.getText().toString().trim());
        store.put(SecureStore.KEY_CARD_EXPIRY, cardExpiry.getText().toString().trim());
        store.put(SecureStore.KEY_CARD_CVC, cardCvc.getText().toString().trim());
        store.put(SecureStore.KEY_CARD_PASSWORD, cardPassword.getText().toString().trim());
        store.put(SecureStore.KEY_CARD_BIRTH, cardBirth.getText().toString().trim());
        store.put(SecureStore.KEY_RESERVATION_URL, reservationUrl.getText().toString().trim());
        int pos = paymentMethod.getSelectedItemPosition();
        store.put(SecureStore.KEY_PAYMENT_METHOD, pos == 1 ? "app_card" : "card");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
