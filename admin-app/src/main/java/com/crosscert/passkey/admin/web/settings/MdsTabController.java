package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.mds.MdsAdminController;
import com.crosscert.passkey.admin.mds.MdsHistoryView;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.admin.mds.MdsStatusView;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.format.Labels;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설정 › MDS Status 탭(← pages/settings/MdsStatusTab.tsx, api/mdsStatus.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다(RP_ADMIN → 403, REST 미호출).
 * 키 관리({@code KeyMgmtController})는 원본 설정 화면 어디에도 없어 이 탭에 두지 않는다.
 *
 * <p>상태({@code MdsAdminController.status})와 이력({@code history(30)})을 함께 읽고 둘 다 끝난 뒤에 그린다. 상태 조회 실패는 로드 실패(LoadError),
 * 이력 조회 실패는 "이력 없음"이 아니라 이력 카드만 LoadError 다(이 경우 헤드라인은 중립 "이력 확인 불가").
 *
 * <p>즉시 갱신({@code POST …/mds/sync} → {@code MdsAdminController.sync}): 긴 작업이라 요청 시간 제한 120초(원본 LONG_TIMEOUT_MS)이고, 요청 중에는 버튼이 비활성 +
 * hx-sync=this:drop 라 겹쳐 보내지 못한다(원본 syncingRef). 결과 SYNCED/SKIPPED/FAILED 별 토스트 후 탭을 다시 읽어 바꾼다. 서버 원문(error)은 싣지 않는다.
 * 폴링은 없다(원본도 없음).
 */
@Slf4j
@Controller
public class MdsTabController {

    static final String TAB_PATH = "/admin/_/settings/tab/mds";
    static final String SYNC_PATH = "/admin/_/settings/mds/sync";
    static final String VIEW = "admin/settings/mds :: tab";
    static final String WARN_VIEW = "admin/settings/mds :: reloadWarning";
    /** 원본 MDS_HISTORY_STRIP_LIMIT, TABLE_ROWS. */
    static final int HISTORY_LIMIT = 30;
    static final int TABLE_ROWS = 6;
    static final int ERROR_PREVIEW_MAX = 80;
    /** 서버 시드(version 0, 1970-01-01) 판정: 2000년 이전은 "한 번도 동기화되지 않음". */
    static final Instant SEED_CUTOFF = Instant.parse("2000-01-01T00:00:00Z");

    private final MdsAdminController mds;
    private final Clock clock;

    public MdsTabController(MdsAdminController mds, Clock clock) {
        this.mds = mds;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    public record Trust(String mode, String label, String description) {}

    public record Rate(String percent, String counts) {}

    public record Run(String time, String version, boolean failed, String changeText, String changeTitle, String tone, String label, String ms) {}

    public record View(String headTone, String headTitle, String sub, String version, String anchors, String lastSync,
                       String nextUpdate, Rate rate, Trust trust, boolean historyOk, List<Run> runs, List<Boolean> strip, String stripLabel) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    public String tab(Model model) {
        SettingsSupport.platform(model);
        load(model, false);
        return VIEW;
    }

    @PostMapping(SYNC_PATH)
    public String sync(HttpServletResponse res, Model model) {
        SettingsSupport.platform(model);
        MdsSchedulerService.SyncResult r;
        try {
            r = mds.sync().data();
        } catch (RuntimeException e) {
            return SettingsSupport.fail(res, "MDS 갱신 실패", e, false); // 오류 상태 + 토스트, 본문 없음(탭은 그대로)
        }
        if ("SYNCED".equals(r.getStatus())) {
            Toasts.successTitled(res, "MDS 즉시 갱신 완료", "버전 " + r.getVersion());
        } else if ("SKIPPED".equals(r.getStatus())) {
            Toasts.warnTitled(res, "MDS 갱신 건너뜀", "다른 인스턴스가 동기화 중입니다.");
        } else {
            // 서버 원문(r.error())은 화면에 싣지 않는다.
            Toasts.errorTitled(res, "MDS 갱신 실패", "갱신하지 못했습니다. 잠시 후 다시 시도하세요.", null, null);
        }
        // 갱신은 성공했다. 다시 읽기(상태·이력 모두)에 실패하면 화면은 그대로 두고 경고만 보인다(원본: 이전 상태·이력을 유지 + 다시 시도).
        if (load(model, true)) return VIEW;
        res.setHeader("HX-Reswap", "none");
        model.addAttribute("reloadError", model.getAttribute("loadError") != null ? model.getAttribute("loadError") : model.getAttribute("historyError"));
        return WARN_VIEW;
    }

    // ── 읽기 ────────────────────────────────────────────────

    /** strict 면 이력 조회 실패도 실패로 본다(false). 아니면 상태만 못 읽을 때 false. */
    private boolean load(Model model, boolean strict) {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH));
        model.addAttribute("syncAttrs", syncAttrs());
        model.addAttribute("loadError", null);
        model.addAttribute("historyError", null);
        model.addAttribute("v", null);
        MdsStatusView status;
        try {
            status = mds.status().data();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[settings] mds status load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("loadError", e);
            return false;
        }
        List<MdsHistoryView> history = null;
        try {
            history = mds.history(HISTORY_LIMIT).data();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[settings] mds history load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("historyError", e);
        }
        model.addAttribute("v", view(status, history, Instant.now(clock)));
        return history != null || !strict;
    }

    static Map<String, Object> syncAttrs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-post", SYNC_PATH);
        m.put("hx-target", "#mds-root");
        m.put("hx-swap", "outerHTML");
        m.put("hx-sync", "this:drop");
        m.put("hx-disabled-elt", "this");
        return m;
    }

    // ── 화면 모델 계산(원본 MdsStatusTab 본문) ───────────────

    static View view(MdsStatusView s, List<MdsHistoryView> history, Instant now) {
        boolean historyOk = history != null;
        List<MdsHistoryView> h = history == null ? List.of() : history;
        boolean neverSynced = s.getVersion() == 0 || isSeedTime(s.getFetchedAt());
        String nextUpdate = s.getNextUpdate() != null && !isSeedDate(s.getNextUpdate()) ? s.getNextUpdate() : null;
        String versionText = !neverSynced ? "#" + s.getVersion() : "—";
        MdsHistoryView latest = h.isEmpty() ? null : h.get(0);
        // 헤드라인의 기준은 상태. 시드뿐이거나 이력을 못 읽었으면 중립, 아니면 가장 최근 실행이 실패일 때만 실패로 표시한다.
        String tone = neverSynced || !historyOk ? "neutral" : latest != null && "FAILED".equals(latest.getStatus()) ? "bad" : "ok";
        String title = "bad".equals(tone) ? "동기화 실패 · BLOB " + versionText
                : "neutral".equals(tone) ? (!historyOk && !neverSynced ? "이력 확인 불가 · BLOB " + versionText : "동기화 이력 없음")
                : "정상 · BLOB " + versionText;
        String sub = "FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다" + (nextUpdate != null ? " · 다음 갱신 " + nextUpdate + " 03:00 KST" : "");
        MdsStatusView.SuccessRate sr = s.getSuccessRate30d();
        Rate rate = sr != null && sr.getTotal() > 0
                ? new Rate(Math.round(sr.getOk() * 100.0 / sr.getTotal()) + "%", sr.getOk() + " / " + sr.getTotal()) : null;
        List<Run> runs = new ArrayList<>();
        List<Boolean> strip = new ArrayList<>();
        long fail = 0;
        for (MdsHistoryView r : h.subList(0, Math.min(h.size(), TABLE_ROWS))) runs.add(run(r));
        for (int i = h.size() - 1; i >= 0; i--) strip.add(stripValue(h.get(i).getStatus()));
        for (MdsHistoryView r : h) if ("FAILED".equals(r.getStatus())) fail++;
        String stripLabel = "최근 " + h.size() + "회 동기화 결과 · 실패 " + fail + "회";
        return new View(tone, title, sub, versionText, String.valueOf(s.getTrustAnchorCount()),
                neverSynced ? "—" : timeAgo(s.getFetchedAt(), now), nextUpdate, rate, trust(s.getTrustMode()), historyOk, runs, strip, stripLabel);
    }

    static Boolean stripValue(String status) {
        return switch (status == null ? "" : status) {
            case "SYNCED" -> Boolean.TRUE;
            case "FAILED" -> Boolean.FALSE;
            default -> null; // 건너뜀·모르는 값: 기록 없음 칸
        };
    }

    static Run run(MdsHistoryView r) {
        String status = r.getStatus() == null ? "" : r.getStatus();
        String tone = switch (status) { case "SYNCED" -> "ok"; case "FAILED" -> "bad"; default -> "neutral"; };
        String label = switch (status) { case "SYNCED" -> "동기화됨"; case "SKIPPED" -> "건너뜀"; case "FAILED" -> "실패"; default -> status; };
        boolean failed = "FAILED".equals(status) && r.getErrorMessage() != null && !r.getErrorMessage().isBlank();
        String change = failed ? failureReason(r.getErrorMessage()) : r.getChangeSummary() != null ? r.getChangeSummary() : "—";
        return new Run(r.getStartedAt() == null ? "—" : KstFormat.kstDateTimeMin(r.getStartedAt()), r.getVersion() != null ? "#" + r.getVersion() : "—",
                failed, change, failed ? flat(r.getErrorMessage()) : null, tone, label, r.getDurationMs() != null ? r.getDurationMs() + "ms" : "—");
    }

    /** 실패 사유(서버 errorMessage)를 한 줄 평문으로. 길면 줄이고(80자) 전체는 title 툴팁(템플릿). */
    static String failureReason(String message) {
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > ERROR_PREVIEW_MAX ? flat.substring(0, ERROR_PREVIEW_MAX - 1) + "…" : flat;
    }

    static String flat(String message) {
        return message == null ? "" : message.replaceAll("\\s+", " ").trim();
    }

    /** 신뢰 모드: 사람이 읽는 라벨 + 설명, 원문은 툴팁. 없으면 null(기본값을 지어내지 않는다). 모르는 값은 원문을 라벨로. */
    static Trust trust(String mode) {
        if (mode == null || mode.isEmpty()) return null;
        Labels.MdsTrustMode known = Labels.MDS_TRUST_MODES.get(mode);
        return known != null ? new Trust(mode, known.getLabel(), known.getDescription()) : new Trust(mode, mode, null);
    }

    static boolean isSeedTime(String iso) {
        if (iso == null || iso.isEmpty()) return true;
        Instant t = KstFormat.parse(iso);
        return t == null || t.isBefore(SEED_CUTOFF);
    }

    static boolean isSeedDate(String date) {
        try {
            return LocalDate.parse(date).getYear() < 2000;
        } catch (RuntimeException e) {
            return isSeedTime(date);
        }
    }

    /** 원본 timeAgo(초 단위): n초 전 / n분 전 / n시간 전 / n일 전. */
    static String timeAgo(String iso, Instant now) {
        Instant t = KstFormat.parse(iso);
        if (t == null) return "—";
        long s = Math.max(0, (now.toEpochMilli() - t.toEpochMilli()) / 1000);
        if (s < 60) return s + "초 전";
        long m = s / 60;
        if (m < 60) return m + "분 전";
        long h = m / 60;
        if (h < 24) return h + "시간 전";
        return (h / 24) + "일 전";
    }
}
