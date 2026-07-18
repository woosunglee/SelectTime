package com.selecttime.hogye;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Encrypted storage for AUC credentials and card secrets.
 */
public final class SecureStore {
    private static final String FILE = "selecttime_secure";

    public static final String KEY_AUC_ID = "auc_id";
    public static final String KEY_AUC_PASSWORD = "auc_password";
    public static final String KEY_CARD_NUMBER = "card_number";
    public static final String KEY_CARD_EXPIRY = "card_expiry";
    public static final String KEY_CARD_CVC = "card_cvc";
    public static final String KEY_CARD_PASSWORD = "card_password";
    public static final String KEY_CARD_BIRTH = "card_birth";
    public static final String KEY_PAYMENT_METHOD = "payment_method";
    public static final String KEY_PREFERRED_TIMES = "preferred_times";
    public static final String KEY_DAYS_AHEAD = "days_ahead";
    public static final String KEY_OPEN_HOUR = "open_hour";
    public static final String KEY_OPEN_MINUTE = "open_minute";
    public static final String KEY_RESERVATION_URL = "reservation_url";

    public static final String DEFAULT_URL =
            "https://www.auc.or.kr/reservation/program/facility/calendar1"
                    + "?facilityCategoryNo=1&menuLevel=2&menuNo=403";

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot open encrypted prefs", e);
        }
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    public String get(String key, String def) {
        return prefs.getString(key, def);
    }

    public void put(String key, String value) {
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }

    public int getInt(String key, int def) {
        return prefs.getInt(key, def);
    }

    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public static String maskCard(String number) {
        if (number == null) {
            return "";
        }
        String digits = number.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return "****";
        }
        return digits.substring(0, 4) + " **** **** " + digits.substring(digits.length() - 4);
    }
}
