package com.selecttime.hogye;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Open-time schedule from settings weekdays:
 * <ol>
 *   <li>Read preferred <b>이용 요일</b> from settings</li>
 *   <li>Find the soonest use-date on a preferred weekday whose
 *       open time (use − {@code days_ahead} @ 15:00) is still in the future</li>
 *   <li>Arm alarms for that open (warm + strike)</li>
 * </ol>
 * With days_ahead=7, open weekday equals use weekday (e.g. 수 15:00 오픈 → 다음 수 이용).
 */
public final class OpenScheduleHelper {
    public static final String WORK_NAME = "selecttime-open";
    public static final String KEY_ARMED = "schedule_armed";
    public static final String KEY_NEXT_AT = "schedule_next_at";
    public static final String KEY_NEXT_USE_DATE = "schedule_next_use_date";

    public static final class NextRun {
        public final long openAtMillis;
        public final String useDate; // YYYY-MM-DD
        public final String openLabel; // display
        public final String useWeekdayLabel; // e.g. 수
        public final String openWeekdayLabel;
        public final String preferredLabel; // e.g. 월·수·금

        public NextRun(long openAtMillis, String useDate, String openLabel,
                       String useWeekdayLabel, String openWeekdayLabel, String preferredLabel) {
            this.openAtMillis = openAtMillis;
            this.useDate = useDate;
            this.openLabel = openLabel;
            this.useWeekdayLabel = useWeekdayLabel == null ? "" : useWeekdayLabel;
            this.openWeekdayLabel = openWeekdayLabel == null ? "" : openWeekdayLabel;
            this.preferredLabel = preferredLabel == null ? "" : preferredLabel;
        }
    }

    private OpenScheduleHelper() {
    }

    public static boolean isArmed(SecureStore store) {
        return store.getBool(KEY_ARMED, false);
    }

    public static void setArmed(SecureStore store, boolean armed) {
        store.putBool(KEY_ARMED, armed);
        if (!armed) {
            store.put(KEY_NEXT_USE_DATE, "");
            store.put("schedule_next_at_ms", "0");
        }
    }

    public static void saveNext(SecureStore store, NextRun next) {
        store.putBool(KEY_ARMED, true);
        store.put(KEY_NEXT_USE_DATE, next.useDate);
        store.put("schedule_next_at_ms", String.valueOf(next.openAtMillis));
    }

    public static long getNextAt(SecureStore store) {
        String raw = store.get("schedule_next_at_ms", "0");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static String getNextUseDate(SecureStore store) {
        return store.get(KEY_NEXT_USE_DATE, "");
    }

    public static String preferredWeekdaysLabel(SecureStore store) {
        return formatPreferredLabel(
                preferredCalendarDays(store.get(SecureStore.KEY_WEEKDAYS, "mon,wed,fri")));
    }

    public static boolean hasPreferredWeekdays(SecureStore store) {
        return !preferredCalendarDays(store.get(SecureStore.KEY_WEEKDAYS, "mon,wed,fri")).isEmpty();
    }

    public static NextRun computeNext(SecureStore store) {
        return computeNext(store, System.currentTimeMillis());
    }

    /**
     * Soonest preferred 이용일 whose 오픈(이용일 − daysAhead @ open hour) is still after {@code nowMs}.
     */
    public static NextRun computeNext(SecureStore store, long nowMs) {
        int daysAhead = store.getInt(SecureStore.KEY_DAYS_AHEAD, 7);
        if (daysAhead < 1) {
            daysAhead = 7;
        }
        int hour = store.getInt(SecureStore.KEY_OPEN_HOUR, 15);
        int minute = store.getInt(SecureStore.KEY_OPEN_MINUTE, 0);
        Set<Integer> preferred = preferredCalendarDays(
                store.get(SecureStore.KEY_WEEKDAYS, "mon,wed,fri"));
        String preferredLabel = formatPreferredLabel(preferred);

        // No weekdays selected → cannot pick a target (caller should block arming).
        if (preferred.isEmpty()) {
            return null;
        }

        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);

        // Scan upcoming 이용일; keep the first whose open time is still in the future.
        for (int useOffset = 0; useOffset <= 28; useOffset++) {
            Calendar useDay = (Calendar) now.clone();
            useDay.add(Calendar.DAY_OF_MONTH, useOffset);
            useDay.set(Calendar.HOUR_OF_DAY, 0);
            useDay.set(Calendar.MINUTE, 0);
            useDay.set(Calendar.SECOND, 0);
            useDay.set(Calendar.MILLISECOND, 0);

            int useDow = useDay.get(Calendar.DAY_OF_WEEK);
            if (!preferred.contains(useDow)) {
                continue;
            }

            Calendar openDay = (Calendar) useDay.clone();
            openDay.add(Calendar.DAY_OF_MONTH, -daysAhead);
            openDay.set(Calendar.HOUR_OF_DAY, hour);
            openDay.set(Calendar.MINUTE, minute);
            openDay.set(Calendar.SECOND, 0);
            openDay.set(Calendar.MILLISECOND, 0);

            if (openDay.getTimeInMillis() <= nowMs) {
                // This 이용일의 오픈은 이미 지남 → 다음 선호 요일
                continue;
            }

            String useDate = String.format(Locale.US, "%04d-%02d-%02d",
                    useDay.get(Calendar.YEAR),
                    useDay.get(Calendar.MONTH) + 1,
                    useDay.get(Calendar.DAY_OF_MONTH));
            String openLabel = String.format(Locale.KOREAN, "%04d-%02d-%02d (%s) %02d:%02d",
                    openDay.get(Calendar.YEAR),
                    openDay.get(Calendar.MONTH) + 1,
                    openDay.get(Calendar.DAY_OF_MONTH),
                    weekdayShort(openDay.get(Calendar.DAY_OF_WEEK)),
                    hour, minute);
            return new NextRun(
                    openDay.getTimeInMillis(),
                    useDate,
                    openLabel,
                    weekdayShort(useDow),
                    weekdayShort(openDay.get(Calendar.DAY_OF_WEEK)),
                    preferredLabel
            );
        }

        return null;
    }

    /**
     * Prepare store for an open-time run.
     * Prefers the armed {@link #KEY_NEXT_USE_DATE} so a late worker/alarm
     * does not drift the booking day; falls back to today + days_ahead.
     */
    public static String prepareOpenRun(Context context) {
        SecureStore store = new SecureStore(context);
        String saved = getNextUseDate(store);
        String useDate;
        if (saved != null && saved.matches("\\d{4}-\\d{2}-\\d{2}")) {
            useDate = saved;
        } else {
            int daysAhead = store.getInt(SecureStore.KEY_DAYS_AHEAD, 7);
            Calendar use = Calendar.getInstance();
            use.add(Calendar.DAY_OF_MONTH, daysAhead);
            useDate = String.format(Locale.US, "%04d-%02d-%02d",
                    use.get(Calendar.YEAR),
                    use.get(Calendar.MONTH) + 1,
                    use.get(Calendar.DAY_OF_MONTH));
        }
        store.put(SecureStore.KEY_USE_DATE, useDate);
        store.putBool(SecureStore.KEY_AUTO_TO_PAYMENT, true);
        store.putBool(SecureStore.KEY_AUTO_CLICK_PAY, true);
        store.putBool(SecureStore.KEY_AUTO_COURT, true);
        return useDate;
    }

    private static String weekdayShort(int calendarDow) {
        switch (calendarDow) {
            case Calendar.SUNDAY:
                return "일";
            case Calendar.MONDAY:
                return "월";
            case Calendar.TUESDAY:
                return "화";
            case Calendar.WEDNESDAY:
                return "수";
            case Calendar.THURSDAY:
                return "목";
            case Calendar.FRIDAY:
                return "금";
            case Calendar.SATURDAY:
                return "토";
            default:
                return "?";
        }
    }

    private static String formatPreferredLabel(Set<Integer> preferred) {
        if (preferred == null || preferred.isEmpty()) {
            return "(미선택)";
        }
        // Display in Mon→Sun order
        int[] order = {
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        };
        List<String> parts = new ArrayList<>();
        for (int d : order) {
            if (preferred.contains(d)) {
                parts.add(weekdayShort(d));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('·');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static Set<Integer> preferredCalendarDays(String csv) {
        // LinkedHashSet keeps insertion order stable for debugging
        Set<Integer> out = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            switch (part.trim().toLowerCase(Locale.ROOT)) {
                case "sun":
                    out.add(Calendar.SUNDAY);
                    break;
                case "mon":
                    out.add(Calendar.MONDAY);
                    break;
                case "tue":
                    out.add(Calendar.TUESDAY);
                    break;
                case "wed":
                    out.add(Calendar.WEDNESDAY);
                    break;
                case "thu":
                    out.add(Calendar.THURSDAY);
                    break;
                case "fri":
                    out.add(Calendar.FRIDAY);
                    break;
                case "sat":
                    out.add(Calendar.SATURDAY);
                    break;
                default:
                    break;
            }
        }
        return out;
    }
}
