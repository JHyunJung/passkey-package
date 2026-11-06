package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.admin.web.support.StandaloneJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 템플릿에서 {@code ${@fmt.status(x)}}, {@code ${@fmt.kst(t)}}, {@code ${@fmt.josa(name,'을/를')}} 처럼 쓰는 표시 도우미 빈.
 * 현재 시각이 필요한 것은 주입된 {@link Clock} 만 쓴다.
 */
@Component("fmt")
public class ViewHelpers {

    private final Clock clock;
    private final ObjectMapper json;

    /** 스프링이 구성한 ObjectMapper(java.time 지원 포함)를 쓴다. */
    @Autowired
    public ViewHelpers(Clock clock, ObjectMapper json) {
        this.clock = clock;
        this.json = json;
    }

    /**
     * 스프링 컨텍스트 없는 단위 테스트용. Jackson 2 시절 {@code new ObjectMapper().findAndRegisterModules()} 와 같은 의미 —
     * Jackson 3 는 java.time 지원이 내장이고, {@code configureForJackson2()} 가 날짜를 timestamp 로 쓰는 Jackson 2 기본값을 되돌린다.
     */
    public ViewHelpers(Clock clock) {
        this(clock, StandaloneJson.jackson2Like().build());
    }

    // ── 라벨 ──
    /** 상태 enum → 한국어(미매핑은 원문). */
    public String status(String v) { return Labels.statusLabel(v); }

    /** AAGUID 정책 모드 enum → 한국어. */
    public String aaguidMode(String v) { return Labels.aaguidMode(v); }

    public Labels.MdsTrustMode mdsTrust(String v) { return v == null ? null : Labels.MDS_TRUST_MODES.get(v); }

    /** 상태 배지 톤(StatusBadge 의 MAP). */
    public String statusTone(String v) {
        if (v == null) return "neutral";
        return switch (v) {
            case "ACTIVE" -> "ok";
            case "REVOKED", "SUSPENDED" -> "bad";
            case "PENDING" -> "warn";
            default -> "neutral";
        };
    }

    // ── 시각 (모두 Asia/Seoul) ──
    /** {@code YYYY-MM-DD HH:mm:ss}. null 은 —. */
    public String kst(OffsetDateTime t) { return KstFormat.kstDateTime(t); }
    public String kst(Instant t) { return KstFormat.kstDateTime(t); }
    public String kst(String iso) { return KstFormat.kstDateTime(iso); }

    /** {@code YYYY-MM-DD HH:mm}. */
    public String kstMin(OffsetDateTime t) { return KstFormat.kstDateTimeMin(t); }
    public String kstMin(Instant t) { return KstFormat.kstDateTimeMin(t); }

    /** {@code YYYY-MM-DD}. */
    public String kstDate(OffsetDateTime t) { return KstFormat.kstDate(t); }
    public String kstDate(Instant t) { return KstFormat.kstDate(t); }
    public String kstDate(String iso) { return KstFormat.kstDate(iso); }

    /** {@code HH:mm}. */
    public String kstTime(OffsetDateTime t) { return KstFormat.kstTime(t); }
    public String kstTime(Instant t) { return KstFormat.kstTime(t); }

    /** ko-KR {@code 2026. 05. 27. 12:00:00} (formatDateTime.ts). */
    public String dateTime(OffsetDateTime t) { return KstFormat.formatDateTime(t); }
    public String dateTime(Instant t) { return KstFormat.formatDateTime(t); }
    public String dateTime(String iso) { return KstFormat.formatDateTime(iso); }

    /** LocalDate → {@code 2026. 05. 27.} (formatDate). */
    public String date(LocalDate d) { return KstFormat.formatDate(d); }
    public String date(String s) { return KstFormat.formatDate(s); }

    public String utcIso(String iso) { return KstFormat.utcIso(iso); }

    /** "5분 전" / "55분 후". */
    public String relative(Instant t) { return KstFormat.relative(t, clock.instant()); }
    public String relative(OffsetDateTime t) { return t == null ? "—" : relative(t.toInstant()); }

    /** "방금 전" / "5분 전" / "2일 전" / "1개월 전". */
    public String timeAgo(Instant t) { return KstFormat.timeAgo(t, clock.instant()); }
    public String timeAgo(OffsetDateTime t) { return t == null ? "—" : timeAgo(t.toInstant()); }

    /** KST 기준 오늘 날짜(파일 이름 등). */
    public LocalDate today() { return KstFormat.kstTodayDate(clock.instant()); }

    // ── 문구 ──
    /** 한글 조사. pair 는 "받침 있을 때/없을 때"(예: '을/를', '이/가'). */
    public String josa(String word, String pair) { return Josa.pick(word, pair); }

    /** 단어 + 조사. */
    public String withJosa(String word, String pair) { return word + Josa.pick(word, pair); }

    // ── API 키·플랫폼 ──
    /** 만료 임박(30일 이내) 여부. 회수 여부는 고려하지 않는다(회수된 키는 따로 거른다). */
    public boolean apiKeyExpiry(OffsetDateTime expiresAt) {
        return ApiKeyExpiry.isExpiringSoon(null, expiresAt, clock.instant());
    }

    /** 회수 여부까지 반영한 만료 임박 판정. */
    public boolean isExpiringSoon(OffsetDateTime revokedAt, OffsetDateTime expiresAt) {
        return ApiKeyExpiry.isExpiringSoon(revokedAt, expiresAt, clock.instant());
    }

    /** User-Agent 로 판단한 명령 팔레트 단축키 표기(⌘K / Ctrl K). */
    public String platform(String userAgent) {
        return PlatformLabels.paletteShortcutLabel(PlatformLabels.isMacPlatform(null, userAgent));
    }

    // ── 오류 ──
    /** 화면에 보일 오류 한 줄("제목. 설명"). */
    public String error(Throwable e) {
        ErrorMessages.Info i = ErrorMessages.describe(e);
        return i.detail() == null || i.detail().isEmpty() ? i.title() : i.title() + ". " + i.detail();
    }

    public ErrorMessages.Info errorInfo(Throwable e) { return ErrorMessages.describe(e); }

    /** 오류 정보 복사용 여러 줄 문구: 대상, 제목, 설명, HTTP 상태(LoadError 의 복사 내용). */
    public String errorText(String what, Throwable e) {
        ErrorMessages.Info i = ErrorMessages.describe(e);
        StringBuilder sb = new StringBuilder(what).append('\n').append(i.title());
        if (i.detail() != null && !i.detail().isEmpty()) sb.append('\n').append(i.detail());
        if (i.httpStatus() != null) sb.append("\nHTTP ").append(i.httpStatus());
        return sb.toString();
    }

    // ── 파일명 ──
    public String recoveryCodeFilename(String email) {
        return RecoveryCodeFilename.of(email, today());
    }

    // ── Alpine 설정 ──
    /** 값을 JSON 으로. 속성 값에 넣으면 Thymeleaf 가 이스케이프한다(th:x-data 등). */
    public String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 직렬화 실패", e);
        }
    }

    /** {@code xdata('chipInput', 'values', v)} → {@code chipInput({"values":…})}. {@code ui:xdata} 속성에 쓴다. */
    public String xdata(String component, Object... keyValues) {
        return component + "(" + cfg(keyValues) + ")";
    }

    /** tabs 조각: items(각 Map 의 'id') 중 active 와 같은 것이 있는지. */
    public boolean hasTab(java.util.List<? extends Map<String, ?>> items, Object active) {
        return items != null && items.stream().anyMatch(t -> java.util.Objects.equals(t.get("id"), active));
    }

    /** 조각의 선택 옵션 Map 을 null 안전하게 읽는다(opts 가 null 이거나 키가 없으면 null). */
    public Object opt(Map<String, ?> opts, String key) {
        return opts == null ? null : opts.get(key);
    }

    /** {@code cfg('values', v, 'mono', true)} → {@code {"values":…,"mono":true}}. Alpine.data 팩토리의 인자로 쓴다. */
    public String cfg(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return json(m);
    }

    // ── 페이지네이션 ──
    /** 보정된 현재 페이지(1..last)와 표시 범위. page 가 범위를 넘으면 마지막 페이지로 맞춘다. */
    public record Pager(int page, int last, long from, long to) {}

    public Pager pager(int page, int pageSize, long total) {
        if (pageSize <= 0) pageSize = 1; // 0 나눗셈 방지
        int last = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        int p = Math.min(Math.max(1, page), last);
        long from = total == 0 ? 0 : (long) (p - 1) * pageSize + 1;
        long to = Math.min(total, (long) p * pageSize);
        return new Pager(p, last, from, to);
    }

    /** {@code base} 에 page(·size) 쿼리를 붙인 주소. base 는 page·size 를 뺀 경로+쿼리. */
    public String pageUrl(String base, int page, Integer size) {
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "page=" + page + (size == null ? "" : "&size=" + size);
    }

    /** 페이지 크기 select 가 값을 이어 붙일 주소(첫 페이지로). */
    public String pageSizeBase(String base) {
        return pageUrl(base, 1, null) + "&size=";
    }
}
