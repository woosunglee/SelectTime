package com.selecttime.hogye;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Official Hogye badminton entry rounds (입장시간 지정제).
 * Source: AUC Hogye gym facility guide.
 */
public final class HogyeTimeSlots {
    public static final class Slot {
        public final String label;       // shown in UI, e.g. "7타임 18:50~20:20"
        public final String startTime;   // matched on reservation page, e.g. "18:50"
        public final String range;       // e.g. "18:50~20:20"

        public Slot(String label, String startTime, String range) {
            this.label = label;
            this.startTime = startTime;
            this.range = range;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Slot> WEEKDAY = Arrays.asList(
            new Slot("1타임  09:00~10:30", "09:00", "09:00~10:30"),
            new Slot("2타임  10:40~12:10", "10:40", "10:40~12:10"),
            new Slot("3타임  12:20~13:50", "12:20", "12:20~13:50"),
            new Slot("4타임  13:55~15:25", "13:55", "13:55~15:25"),
            new Slot("5타임  15:30~17:00", "15:30", "15:30~17:00"),
            new Slot("6타임  17:10~18:40", "17:10", "17:10~18:40"),
            new Slot("7타임  18:50~20:20", "18:50", "18:50~20:20"),
            new Slot("8타임  20:30~22:00", "20:30", "20:30~22:00")
    );

    private static final List<Slot> SUNDAY_HOLIDAY = Arrays.asList(
            new Slot("1타임  09:00~10:30", "09:00", "09:00~10:30"),
            new Slot("2타임  10:30~12:00", "10:30", "10:30~12:00"),
            new Slot("3타임  12:00~13:30", "12:00", "12:00~13:30"),
            new Slot("4타임  13:30~15:00", "13:30", "13:30~15:00"),
            new Slot("5타임  15:00~16:30", "15:00", "15:00~16:30"),
            new Slot("6타임  16:30~18:00", "16:30", "16:30~18:00")
    );

    private HogyeTimeSlots() {
    }

    public static List<Slot> forDate(int year, int monthZeroBased, int dayOfMonth) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, monthZeroBased, dayOfMonth);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SUNDAY) {
            return SUNDAY_HOLIDAY;
        }
        return WEEKDAY;
    }

    public static CharSequence[] labels(List<Slot> slots) {
        CharSequence[] out = new CharSequence[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            out[i] = slots.get(i).label;
        }
        return out;
    }

    public static int indexOfStart(List<Slot> slots, String startTime) {
        if (startTime == null || slots == null) {
            return 0;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (startTime.equals(slots.get(i).startTime)
                    || startTime.equals(slots.get(i).range)) {
                return i;
            }
        }
        return 0;
    }

    public static List<Slot> weekdaySlots() {
        return WEEKDAY;
    }

    public static List<Slot> sundaySlots() {
        return SUNDAY_HOLIDAY;
    }

    /** Court labels used on AUC Hogye reservation (1~12). */
    public static CharSequence[] courtLabels() {
        CharSequence[] out = new CharSequence[12];
        for (int i = 0; i < 12; i++) {
            out[i] = (i + 1) + "코트";
        }
        return out;
    }

    public static boolean[] courtsCheckedFromCsv(String csv) {
        boolean[] checked = new boolean[12];
        if (csv == null || csv.trim().isEmpty()) {
            return checked;
        }
        for (String part : csv.split(",")) {
            String digits = part.replaceAll("\\D", "");
            if (digits.isEmpty()) {
                continue;
            }
            try {
                int n = Integer.parseInt(digits);
                if (n >= 1 && n <= 12) {
                    checked[n - 1] = true;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return checked;
    }

    public static String courtsCsvFromChecked(boolean[] checked) {
        StringBuilder sb = new StringBuilder();
        if (checked == null) {
            return "";
        }
        for (int i = 0; i < checked.length && i < 12; i++) {
            if (!checked[i]) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i + 1).append("코트");
        }
        return sb.toString();
    }

    /** Build storage value: startTime,range (and extras for multi preference). */
    public static String toPreferredTimesValue(Slot slot) {
        if (slot == null) {
            return "";
        }
        return slot.startTime + "," + slot.range;
    }

    public static String toPreferredTimesValue(List<Slot> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.startTime).append(',').append(s.range);
        }
        return sb.toString();
    }

    public static List<Slot> matchPreferredFromStored(String stored, List<Slot> catalog) {
        List<Slot> out = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty() || catalog == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String part : stored.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            for (Slot s : catalog) {
                if ((p.equals(s.startTime) || p.equals(s.range) || (s.label != null && s.label.contains(p)))
                        && seen.add(s.startTime)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    public static String displayPreferredTimes(String stored, List<Slot> catalog) {
        List<Slot> matched = matchPreferredFromStored(stored, catalog);
        if (matched.isEmpty()) {
            return stored == null ? "" : stored;
        }
        StringBuilder sb = new StringBuilder();
        for (Slot s : matched) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.label.trim());
        }
        return sb.toString();
    }
}
