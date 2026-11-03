package com.crosscert.passkey.admin.web.format;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 표시용 KST(Asia/Seoul) 포매터(← admin-ui lib/kst.ts, lib/formatDateTime.ts).
 * 시간대는 항상 Asia/Seoul 을 명시한다(JVM 기본 시간대 사용 안 함). 문자열 입력은 ISO(Z 또는 +09:00),
 * 파싱할 수 없으면 원문을 그대로 돌려준다.
 */
public final class KstFormat {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DASH = "—";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** ko-KR 표기. "2026. 05. 27. 12:00:00" (자정은 00). */
    private static final DateTimeFormatter KO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm:ss");
    private static final DateTimeFormatter UTC_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final Pattern LOCAL_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final long DAY_SECONDS = 86_400;

    private KstFormat() {}

    // ── 파싱 ────────────────────────────────────────────────

    /** ISO 문자열 → Instant. 파싱할 수 없으면 null. */
    public static Instant parse(String iso) {
        if (iso == null) return null;
        try {
            return OffsetDateTime.parse(iso.strip()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso.strip());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static ZonedDateTime kst(Instant t) {
        return t.atZone(KST);
    }

    // ── kst.ts ───────────────────────────────────────────────

    /** KST 날짜 {@code YYYY-MM-DD}. */
    public static String kstDate(String iso) {
        Instant t = parse(iso);
        return t == null ? iso : kstDate(t);
    }

    public static String kstDate(Instant t) {
        return t == null ? DASH : DATE.format(kst(t));
    }

    public static String kstDate(OffsetDateTime t) {
        return t == null ? DASH : kstDate(t.toInstant());
    }

    /** KST 시각 {@code HH:mm}. */
    public static String kstTime(String iso) {
        Instant t = parse(iso);
        return t == null ? iso : kstTime(t);
    }

    public static String kstTime(Instant t) {
        return t == null ? DASH : TIME.format(kst(t));
    }

    public static String kstTime(OffsetDateTime t) {
        return t == null ? DASH : kstTime(t.toInstant());
    }

    /** KST {@code YYYY-MM-DD HH:mm:ss}. */
    public static String kstDateTime(String iso) {
        Instant t = parse(iso);
        return t == null ? iso : kstDateTime(t);
    }

    public static String kstDateTime(Instant t) {
        return t == null ? DASH : DATE_TIME.format(kst(t));
    }

    public static String kstDateTime(OffsetDateTime t) {
        return t == null ? DASH : kstDateTime(t.toInstant());
    }

    /** KST {@code YYYY-MM-DD HH:mm}. */
    public static String kstDateTimeMin(String iso) {
        Instant t = parse(iso);
        return t == null ? iso : kstDateTimeMin(t);
    }

    public static String kstDateTimeMin(Instant t) {
        return t == null ? DASH : DATE_TIME_MIN.format(kst(t));
    }

    public static String kstDateTimeMin(OffsetDateTime t) {
        return t == null ? DASH : kstDateTimeMin(t.toInstant());
    }

    /** 지금의 KST 날짜 {@code YYYY-MM-DD}. */
    public static String kstToday(Instant now) {
        return kstDate(now);
    }

    /** KST 기준 오늘 날짜(파일 이름 등). */
    public static LocalDate kstTodayDate(Instant now) {
        return kst(now).toLocalDate();
    }

    public record DateRange(String from, String to) {}

    /** KST 기준 직전 달 1일 ~ 말일({@code YYYY-MM-DD}). */
    public static DateRange kstPrevMonthRange(Instant now) {
        LocalDate first = kst(now).toLocalDate().withDayOfMonth(1).minusMonths(1);
        return new DateRange(DATE.format(first), DATE.format(first.withDayOfMonth(first.lengthOfMonth())));
    }

    /** UTC ISO {@code YYYY-MM-DDTHH:mm:ssZ}(밀리초 제거). 파싱할 수 없으면 원문. */
    public static String utcIso(String iso) {
        Instant t = parse(iso);
        return t == null ? iso : UTC_ISO.format(t.atOffset(ZoneOffset.UTC));
    }

    /** 피드 날짜 그룹 라벨. KST 기준 오늘/어제, 그 외 {@code YYYY-MM-DD}. KST 는 DST 가 없어 24h 빼기로 전날을 구한다. */
    public static String dayLabel(String iso, Instant now) {
        String key = kstDate(iso);
        if (key.equals(kstDate(now))) return "오늘";
        if (key.equals(kstDate(now.minusSeconds(DAY_SECONDS)))) return "어제";
        return key;
    }

    public record DayGroup<T>(String label, List<T> items) {}

    /** 입력 순서를 유지하며 같은 dayLabel 의 연속 구간을 하나의 그룹으로 묶는다(피드는 최신순). */
    public static <T> List<DayGroup<T>> groupByDay(List<T> items, Function<T, String> isoOf, Instant now) {
        List<DayGroup<T>> groups = new ArrayList<>();
        for (T it : items) {
            String label = dayLabel(isoOf.apply(it), now);
            DayGroup<T> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && last.label().equals(label)) last.items().add(it);
            else {
                List<T> g = new ArrayList<>();
                g.add(it);
                groups.add(new DayGroup<>(label, g));
            }
        }
        return groups;
    }

    // ── formatDateTime.ts ───────────────────────────────────

    /** ko-KR 날짜·시각 {@code 2026. 05. 27. 12:00:00}. null·빈 값은 em-dash, 파싱 실패는 원문. */
    public static String formatDateTime(String iso) {
        if (iso == null || iso.isEmpty()) return DASH;
        Instant t = parse(iso);
        return t == null ? iso : formatDateTime(t);
    }

    public static String formatDateTime(Instant t) {
        return t == null ? DASH : KO_DATE_TIME.format(kst(t));
    }

    public static String formatDateTime(OffsetDateTime t) {
        return t == null ? DASH : formatDateTime(t.toInstant());
    }

    /** LocalDate({@code YYYY-MM-DD}) 값을 시간 계산 없이 {@code 2026. 05. 27.} 로. null·빈 값은 em-dash, 형식이 다르면 원문. */
    public static String formatDate(String value) {
        if (value == null || value.isEmpty()) return DASH;
        Matcher m = LOCAL_DATE.matcher(value);
        return m.matches() ? m.group(1) + ". " + m.group(2) + ". " + m.group(3) + "." : value;
    }

    public static String formatDate(LocalDate d) {
        return d == null ? DASH : formatDate(d.toString());
    }

    // ── 상대 시각 ────────────────────────────────────────────

    /** "5분 전" / "55분 후" / "3시간 전" / "2일 후" (← LicensePage.relativeTime). */
    public static String relative(Instant t, Instant now) {
        if (t == null) return DASH;
        long diffMs = t.toEpochMilli() - now.toEpochMilli();
        boolean future = diffMs > 0;
        String suffix = future ? "후" : "전";
        long mins = Math.abs(diffMs) / 60_000;
        if (mins < 1) return future ? "곧" : "방금";
        if (mins < 60) return mins + "분 " + suffix;
        long hours = mins / 60;
        if (hours < 48) return hours + "시간 " + suffix;
        return (hours / 24) + "일 " + suffix;
    }

    /** "방금 전" / "5분 전" / "3시간 전" / "2일 전" / "1개월 전" (← AuditChainPage.timeAgo). */
    public static String timeAgo(Instant t, Instant now) {
        if (t == null) return DASH;
        long m = (now.toEpochMilli() - t.toEpochMilli()) / 60_000;
        if (m < 1) return "방금 전";
        if (m < 60) return m + "분 전";
        long h = m / 60;
        if (h < 24) return h + "시간 전";
        long d = h / 24;
        if (d < 30) return d + "일 전";
        return (d / 30) + "개월 전";
    }
}
