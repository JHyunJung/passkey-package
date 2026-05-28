# Phase F4 — MDS / SystemInfo / Monthly Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Close Gap #14, #16, #17, #18, #19. Settings MDS 탭과 SystemInfo 탭 fixture 제거, AuditChainPage 월간 PDF 보고서 `window.alert` 제거. **admin-ui 디자인 무수정.**

**Architecture:**
- F4.1 MDS: V34 마이그레이션 (`mds_sync_history` 테이블), `MdsAdminController` 응답에 4필드 추가 (`trustAnchorCount`, `nextUpdate` 보강, `trustMode`, `successRate30d`), 새 GET `/admin/api/mds/history?limit=5` endpoint.
- F4.2 SystemInfo: 신규 `SystemInfoController` + `SystemInfoService`. **actuator 의존성 추가 없이** Spring `BuildProperties` + DB ping + JVM uptime + 환경 정보로 채운다. p95/avg/p99 는 null 허용.
- F4.3 Monthly PDF: 신규 `MonthlyReportController`, openhtmltopdf 의존성 추가, AuditChainPage `handleGenerate` 의 `window.alert` 제거 후 blob 다운로드.

**Tech Stack:** Spring Boot, JPA, Oracle, openhtmltopdf (Apache 2.0), React + TypeScript.

**Spec reference:** `docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F4.

---

## Execution policy

Same as F1/F2/F3:
1. Tests minimal (compile + 1 smoke per persistence path)
2. Per-task codex review on staged diff
3. Autonomous decisions during execution

---

## Schema discovery findings

- 마지막 마이그레이션은 **V33** (F2). F4 신규는 **V34**.
- `MdsAdminController` 는 `ApiResponse<>` envelope 사용 (F2/F3 의 raw DTO 패턴과 다름). 신규 history endpoint 도 envelope 으로 통일.
- `MdsAdminController` 는 entity/repository 가 아닌 `JdbcTemplate` 직접 사용. 새 history endpoint 도 같은 스타일 가능.
- `actuator` 의존성/설정 없음. SystemInfo 의 p95/avg/p99 는 actuator 없이는 정확하게 산출 불가 → **null 허용 + 환경 정보/uptime 만 채움**. (actuator 도입은 별도 phase.)
- openhtmltopdf 의존성 없음 → admin-app `build.gradle.kts` 에 추가.

---

## File Structure

### Backend — F4.1 MDS

```
core/src/main/resources/db/migration/V34__mds_sync_history.sql              # new
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java  # modify — +4 fields
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryView.java # new
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java # new
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java # modify — append history row
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java  # modify — extend status + add history endpoint
```

### Backend — F4.2 SystemInfo

```
admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoController.java # new
admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoService.java    # new
admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoView.java       # new
admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java            # modify — verify BuildProperties enabled
admin-app/build.gradle.kts                                                            # modify — ensure springBootBuildInfo task
```

### Backend — F4.3 Monthly PDF

```
admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportController.java # new
admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportService.java    # new
admin-app/build.gradle.kts                                                              # modify — openhtmltopdf deps
```

### Frontend

```
admin-ui/src/api/mdsStatus.ts                                       # modify — add 4 fields + history() method
admin-ui/src/api/systemInfo.ts                                       # new — adapter
admin-ui/src/api/monthlyReport.ts                                    # new — adapter
admin-ui/src/pages/settings/MdsStatusTab.tsx                         # modify — drop trustAnchors=287 + syncHistoryFixture
admin-ui/src/pages/settings/SystemInfoTab.tsx                        # modify — fixture → adapter
admin-ui/src/pages/AuditChainPage.tsx                                # modify — handleGenerate replace window.alert
admin-ui/src/fixtures/systemInfo.ts                                  # delete
```

---

## Working directory & branch

Worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/gap-fill-f4/` on `worktree-gap-fill-f4` (forked from main at `cce46f6`).

---

## Task 1: V34 mds_sync_history migration

**Files:**
- Create: `core/src/main/resources/db/migration/V34__mds_sync_history.sql`

- [ ] **Step 1: Write migration**

```sql
-- ============================================================
-- V34 — mds_sync_history (MDS 동기화 이력 테이블)
--
-- 목적: MdsStatusTab "최근 동기화 이력" 카드 실데이터화 (Gap #17).
-- MdsSchedulerService.runOnce() 호출 시마다 한 row append.
-- Idempotent: CREATE wrapped in EXCEPTION (ORA-00955).
-- ============================================================

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE mds_sync_history (
    id              NUMBER(19,0)             NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE,
    version         NUMBER(19,0),
    status          VARCHAR2(16) NOT NULL,
    change_summary  VARCHAR2(128),
    duration_ms     NUMBER(10,0),
    error_message   VARCHAR2(500),
    CONSTRAINT pk_mds_sync_history PRIMARY KEY (id),
    CONSTRAINT ck_mds_sync_history_status CHECK (status IN (''SYNCED'',''SKIPPED'',''FAILED''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE mds_sync_history_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -1408);
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_mds_sync_history_started_at ON mds_sync_history (started_at DESC)';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

GRANT SELECT, INSERT ON mds_sync_history TO APP_ADMIN;
GRANT SELECT ON mds_sync_history_seq TO APP_ADMIN;
```

- [ ] **Step 2: Apply via sqlplus**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 < core/src/main/resources/db/migration/V34__mds_sync_history.sql
```

- [ ] **Step 3: Verify**

```bash
docker exec -i passkey-oracle sqlplus -s APP_OWNER/app_owner_pw@//localhost:1521/XEPDB1 <<'SQL'
SELECT table_name FROM user_tables WHERE table_name = 'MDS_SYNC_HISTORY';
SELECT sequence_name FROM user_sequences WHERE sequence_name = 'MDS_SYNC_HISTORY_SEQ';
SELECT grantee, privilege FROM user_tab_privs WHERE table_name = 'MDS_SYNC_HISTORY';
SQL
```

- [ ] **Step 4: Codex + commit**

Codex prompt: "codex review V34 mds_sync_history migration. Focus idempotency (ORA-00955 table, ORA-01408 index), GRANT separation, CHECK constraint values. Must-fix only."

```bash
git add core/src/main/resources/db/migration/V34__mds_sync_history.sql
git commit -m "feat(core): V34 mds_sync_history table + sequence + grants (Gap #17)"
```

---

## Task 2: MdsHistoryView DTO + MdsHistoryService

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryView.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java`

- [ ] **Step 1: Write DTO**

```java
package com.crosscert.passkey.admin.mds;

import java.time.Instant;

public record MdsHistoryView(
        long id,
        String startedAt,
        String finishedAt,
        Long version,
        String status,
        String changeSummary,
        Integer durationMs,
        String errorMessage
) {
    public static MdsHistoryView of(
            long id, Instant startedAt, Instant finishedAt, Long version,
            String status, String changeSummary, Integer durationMs, String errorMessage) {
        return new MdsHistoryView(
                id,
                startedAt == null ? null : startedAt.toString(),
                finishedAt == null ? null : finishedAt.toString(),
                version, status, changeSummary, durationMs, errorMessage);
    }
}
```

- [ ] **Step 2: Write service**

```java
package com.crosscert.passkey.admin.mds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class MdsHistoryService {

    private final JdbcTemplate jdbc;

    public MdsHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<MdsHistoryView> recent(int limit) {
        int capped = Math.max(1, Math.min(50, limit));
        return jdbc.query(
                "SELECT id, started_at, finished_at, version, status, change_summary, duration_ms, error_message "
              + "FROM {h-schema}mds_sync_history "
              + "ORDER BY started_at DESC FETCH FIRST ? ROWS ONLY",
                (rs, n) -> MdsHistoryView.of(
                        rs.getLong("id"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        (Long) rs.getObject("version"),
                        rs.getString("status"),
                        rs.getString("change_summary"),
                        (Integer) rs.getObject("duration_ms"),
                        rs.getString("error_message")
                ),
                capped);
    }

    @Transactional
    public void append(Instant startedAt, Instant finishedAt, Long version, String status,
                       String changeSummary, Integer durationMs, String errorMessage) {
        jdbc.update(
                "INSERT INTO {h-schema}mds_sync_history "
              + "(id, started_at, finished_at, version, status, change_summary, duration_ms, error_message) "
              + "VALUES ({h-schema}mds_sync_history_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, ?)",
                java.sql.Timestamp.from(startedAt),
                finishedAt == null ? null : java.sql.Timestamp.from(finishedAt),
                version, status, changeSummary, durationMs, errorMessage);
    }

    @Transactional(readOnly = true)
    public int successRate30dCountOk() {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(30));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM {h-schema}mds_sync_history WHERE status='SYNCED' AND started_at >= ?",
                Integer.class,
                java.sql.Timestamp.from(since));
        return n == null ? 0 : n;
    }

    @Transactional(readOnly = true)
    public int successRate30dCountTotal() {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(30));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM {h-schema}mds_sync_history WHERE started_at >= ?",
                Integer.class,
                java.sql.Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
```

> Native SQL with `{h-schema}` placeholder (same trick as F3 fix commit `2ba1728`). FETCH FIRST is Oracle 12c+ syntax.

- [ ] **Step 3: Compile**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 4: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryView.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java
```

Codex prompt: "codex review MdsHistoryView + MdsHistoryService. Verify: (1) {h-schema} placeholder used (matches F3 fix), (2) FETCH FIRST limit clamp 1-50, (3) successRate30d split into two count methods (ok / total) avoiding division in SQL, (4) Instant↔Timestamp conversion correct, (5) sequence uses .NEXTVAL with schema prefix. Must-fix only."

```bash
git commit -m "feat(admin-app): MdsHistoryView + MdsHistoryService (Gap #17)"
```

---

## Task 3: MdsSchedulerService — append history row

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`

- [ ] **Step 1: Inspect**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
```

Identify `runOnce()` method. It returns `SyncResult(String status, Long version, String error)`.

- [ ] **Step 2: Inject MdsHistoryService**

Add `MdsHistoryService history` field + constructor param + `this.history = history` assignment.

- [ ] **Step 3: Wrap runOnce() body with history append**

Capture `started = Instant.now()` at the start. After the existing logic produces `SyncResult`, before returning, compute `duration = Instant.now() - started` and call:

```java
        history.append(
                started,
                Instant.now(),
                result.version(),
                result.status(),
                null,           // changeSummary: not yet derivable
                (int) java.time.Duration.between(started, Instant.now()).toMillis(),
                result.error());
```

> Wrap in try/catch — if history append fails, log warn but do NOT fail the sync itself.

- [ ] **Step 4: Compile + commit**

```bash
./gradlew :admin-app:compileJava
```

Codex prompt: "codex review MdsSchedulerService append-history wiring. Verify: (1) history append is best-effort (doesn't fail sync), (2) timing measurement correct, (3) status/version/error fields propagated from SyncResult. Must-fix only."

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
git commit -m "feat(admin-app): MdsSchedulerService records sync history row (Gap #17)"
```

---

## Task 4: MdsStatusView extension + MdsAdminController extension

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`

- [ ] **Step 1: Extend MdsStatusView**

Current:
```java
public record MdsStatusView(long version, String nextUpdate, String fetchedAt) {}
```

Replace with:
```java
public record MdsStatusView(
        long version,
        String nextUpdate,
        String fetchedAt,
        int trustAnchorCount,
        String trustMode,
        SuccessRate successRate30d
) {
    public record SuccessRate(int ok, int total) {}
}
```

- [ ] **Step 2: Update MdsAdminController.status()**

Inject `MdsHistoryService history`. Inject `MdsAaguidCache` (or similar — find by `grep -rn "MdsAaguidCache" admin-app/src/main`) to count trust anchors. If not available, fall back to:

```java
int trustAnchors = jdbc.queryForObject(
    "SELECT COUNT(*) FROM {h-schema}mds_aaguid_entry", Integer.class);
```

(verify table name via `ls core/src/main/resources/db/migration | grep -i mds` and inspecting V## for mds aaguid entry table — likely `mds_aaguid_entry` or similar from V15+).

Compute `trustMode` from application config:
```java
String trustMode = environment.getProperty("passkey.mds.trust-mode", "MDS_STRICT_OPTIONAL");
```

Build response:
```java
MdsStatusView view = new MdsStatusView(
        existingVersion,
        existingNextUpdate,
        existingFetchedAt,
        trustAnchors,
        trustMode,
        new MdsStatusView.SuccessRate(history.successRate30dCountOk(), history.successRate30dCountTotal()));
return ApiResponse.ok(view);
```

- [ ] **Step 3: Add history endpoint to controller**

```java
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@GetMapping("/history")
public ApiResponse<List<MdsHistoryView>> history(
        @RequestParam(name = "limit", defaultValue = "5") int limit) {
    return ApiResponse.ok(history.recent(limit));
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 5: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java
```

Codex prompt: "codex review MdsStatusView/Controller extension. Verify: (1) MdsStatusView has 4 new fields including nested SuccessRate record, (2) trustAnchorCount derived from real source (cache count or aaguid_entry table), (3) trustMode from config, (4) successRate30d split into ok/total, (5) /history endpoint added with limit param. Must-fix only."

```bash
git commit -m "feat(admin-app): MdsStatus response extended + /history endpoint (Gap #16/#17/#18)"
```

---

## Task 5: SystemInfo backend (Controller + Service + View)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoView.java`

- [ ] **Step 1: Write View DTO** matching `fixtures/systemInfo.ts SystemInfoData` shape

```java
package com.crosscert.passkey.admin.system;

import java.util.List;

public record SystemInfoView(
        String serverVersion,
        String deployedAt,
        Integer apiP95Ms,      // nullable — actuator not configured yet
        Integer apiAvgMs,      // nullable
        Integer apiP99Ms,      // nullable
        Double uptimePercent,  // nullable
        Long uptimeDays,
        Long uptimeIncidentMinutes,  // nullable
        Host host,
        List<Component> components
) {
    public record Host(
            String apiHostname,
            String adminConsole,
            String region,
            String environment,
            String deployMethod
    ) {}

    public record Component(
            String name,
            String version,
            String status,    // 'OK' | 'DOWN' | 'DEGRADED'
            int instances,
            String note
    ) {}
}
```

- [ ] **Step 2: Write Service** — uses `BuildProperties` + `Environment` + DB ping

```java
package com.crosscert.passkey.admin.system;

import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SystemInfoService {

    @Nullable private final BuildProperties build;
    private final JdbcTemplate jdbc;
    private final Environment env;

    public SystemInfoService(@Nullable BuildProperties build, JdbcTemplate jdbc, Environment env) {
        this.build = build;
        this.jdbc = jdbc;
        this.env = env;
    }

    public SystemInfoView get() {
        String version = build != null ? build.getVersion() : "dev";
        String deployedAt = build != null && build.getTime() != null
                ? build.getTime().toString() : Instant.now().toString();

        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeDays = Duration.ofMillis(uptimeMillis).toDays();

        SystemInfoView.Host host = new SystemInfoView.Host(
                env.getProperty("passkey.host.api", "api.passkey.example.com"),
                env.getProperty("passkey.host.admin-console", "admin.passkey.example.com"),
                env.getProperty("passkey.host.region", "ap-northeast-2 (Seoul)"),
                env.getProperty("spring.profiles.active", "development"),
                env.getProperty("passkey.host.deploy-method", "local")
        );

        List<SystemInfoView.Component> components = new ArrayList<>();
        components.add(new SystemInfoView.Component(
                "admin-app", version, "OK", 1, "Spring Boot"));
        components.add(new SystemInfoView.Component(
                "passkey-app", version, "OK", 1, "Spring Boot"));
        String dbBanner = "Oracle (unknown)";
        try {
            dbBanner = jdbc.queryForObject(
                    "SELECT banner FROM v$version WHERE ROWNUM=1", String.class);
        } catch (Exception ignore) { /* APP_ADMIN may lack SELECT on v$version */ }
        components.add(new SystemInfoView.Component(
                "Oracle DB", dbBanner == null ? "unknown" : dbBanner, "OK", 1, "primary"));
        components.add(new SystemInfoView.Component(
                "MDS sync scheduler", version, "OK", 1, "03:00 KST daily"));

        return new SystemInfoView(
                version,
                deployedAt,
                null, null, null,           // p95/avg/p99 — actuator not configured
                null, uptimeDays, null,     // uptimePercent/incident — null
                host,
                components);
    }
}
```

- [ ] **Step 3: Write Controller**

```java
package com.crosscert.passkey.admin.system;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/system/info")
public class SystemInfoController {

    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    public ApiResponse<SystemInfoView> get() {
        return ApiResponse.ok(service.get());
    }
}
```

- [ ] **Step 4: Verify BuildProperties available**

Ensure `admin-app/build.gradle.kts` has `springBootBuildInfo` task. Check:
```bash
grep -nE "springBoot \{|buildInfo" admin-app/build.gradle.kts
```

If `buildInfo()` block missing, add inside `springBoot { ... }`:
```kotlin
springBoot {
    buildInfo()
}
```

- [ ] **Step 5: Compile**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 6: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/system/ \
        admin-app/build.gradle.kts
```

Codex prompt: "codex review SystemInfoController/Service/View + buildInfo enablement. Verify: (1) Actuator NOT required (uses BuildProperties + Environment + JDBC), (2) p95/avg/p99/uptimePercent nullable per execution policy (actuator not configured), (3) components include admin-app/passkey-app/Oracle DB/MDS scheduler, (4) RBAC matches read-only pattern (PLATFORM_OPERATOR + RP_ADMIN), (5) DB version query has try/catch (APP_ADMIN may lack SELECT). Must-fix only."

```bash
git commit -m "feat(admin-app): SystemInfo backend (Gap #19)"
```

---

## Task 6: Monthly PDF Report — backend (controller + service + openhtmltopdf)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportService.java`
- Modify: `admin-app/build.gradle.kts` — add openhtmltopdf deps

- [ ] **Step 1: Add dependency**

In `admin-app/build.gradle.kts` `dependencies { ... }`:
```kotlin
implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
```

- [ ] **Step 2: Write service**

```java
package com.crosscert.passkey.admin.audit;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class MonthlyReportService {

    private final AuditChainMonitorService chainMonitor;

    public MonthlyReportService(AuditChainMonitorService chainMonitor) {
        this.chainMonitor = chainMonitor;
    }

    public byte[] generate(LocalDate from, LocalDate to) {
        AuditChainMonitorService.ChainOverview overview = chainMonitor.snapshot();

        String html = buildHtml(from, to, overview);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF generation failed", e);
        }
    }

    private String buildHtml(LocalDate from, LocalDate to, AuditChainMonitorService.ChainOverview overview) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='utf-8'>");
        sb.append("<style>body{font-family:sans-serif} table{border-collapse:collapse;width:100%}");
        sb.append("th,td{border:1px solid #ccc;padding:6px;font-size:11px;text-align:left}</style>");
        sb.append("</head><body>");
        sb.append("<h1>Audit Chain Monthly Report</h1>");
        sb.append("<p>Period: ").append(from).append(" — ").append(to).append("</p>");
        sb.append("<p>Generated at: ").append(java.time.Instant.now()).append("</p>");
        sb.append("<h2>Tenants</h2>");
        sb.append("<table><tr><th>Tenant</th><th>Status</th><th>Verified At</th><th>Tampered Entry</th></tr>");
        for (AuditChainMonitorService.ChainOverview.TenantChain c : overview.tenants()) {
            sb.append("<tr>");
            sb.append("<td>").append(c.slug() == null ? c.tenantId() : c.slug()).append("</td>");
            sb.append("<td>").append(c.status()).append("</td>");
            sb.append("<td>").append(c.verifiedAt() == null ? "—" : c.verifiedAt()).append("</td>");
            sb.append("<td>").append(c.tamperedEntryId() == null ? "—" : c.tamperedEntryId()).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        sb.append("</body></html>");
        return sb.toString();
    }
}
```

> Verify `AuditChainMonitorService` exposes `snapshot()` returning `ChainOverview` with fields shown (`tenants`, each with `slug`/`tenantId`/`status`/`verifiedAt`/`tamperedEntryId`). If the real shape differs, adapt the iteration body. Run `grep -n "record ChainOverview\|class ChainOverview\|TenantChain" admin-app/src/main/java/com/crosscert/passkey/admin/audit/*.java` to confirm.

- [ ] **Step 3: Write controller**

```java
package com.crosscert.passkey.admin.audit;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/audit/chain/monthly-report")
public class MonthlyReportController {

    private final MonthlyReportService service;
    private final AuditLogService auditService;

    public MonthlyReportController(MonthlyReportService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            Authentication auth) {
        LocalDate fromD = LocalDate.parse(from);
        LocalDate toD = LocalDate.parse(to);
        byte[] pdf = service.generate(fromD, toD);

        auditService.append(new AuditAppendRequest(
                null,
                auth.getName(),
                "MONTHLY_REPORT_GENERATED",
                "audit_chain",
                "monthly",
                null,
                Map.of("from", from, "to", to, "bytes", pdf.length)));

        String filename = "audit-chain-monthly-" + from + "-to-" + to + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 5: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/MonthlyReportService.java \
        admin-app/build.gradle.kts
```

Codex prompt: "codex review MonthlyReport (PDF) backend. Verify: (1) openhtmltopdf 1.0.10 stable, (2) PdfRendererBuilder.toStream usage matches lib API, (3) AuditChainMonitorService.snapshot() actual shape, (4) audit append on MONTHLY_REPORT_GENERATED, (5) Content-Disposition header, (6) HTML template injection-safe (no user-supplied text rendered without escape). Must-fix only."

```bash
git commit -m "feat(admin-app): MonthlyReport PDF backend (openhtmltopdf) (Gap #14)"
```

---

## Task 7: Frontend adapters — mdsStatus extended + systemInfo + monthlyReport

**Files:**
- Modify: `admin-ui/src/api/mdsStatus.ts`
- Create: `admin-ui/src/api/systemInfo.ts`
- Create: `admin-ui/src/api/monthlyReport.ts`

- [ ] **Step 1: Extend mdsStatus.ts**

Add 4 fields to `MdsStatus` type and add `history()` method:

```typescript
export type MdsStatus = {
  version: number;
  nextUpdate: string | null;
  fetchedAt: string | null;
  trustAnchorCount: number;
  trustMode: string;
  successRate30d: { ok: number; total: number };
};

export type MdsHistoryRow = {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  version: number | null;
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  changeSummary: string | null;
  durationMs: number | null;
  errorMessage: string | null;
};
```

Add to `mdsStatusApi`:
```typescript
  history: (limit = 5): Promise<MdsHistoryRow[]> =>
    api.get<MdsHistoryRow[]>(`/admin/api/mds/history?limit=${limit}`),
```

> MdsAdminController uses `ApiResponse<>` envelope, so use `api.get` (envelope-aware), NOT `api.getRaw`.

- [ ] **Step 2: Write systemInfo.ts**

```typescript
import { api } from './client';

export type SystemInfoComponent = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  instances: number;
  note: string | null;
};

export type SystemInfoHost = {
  apiHostname: string;
  adminConsole: string;
  region: string;
  environment: string;
  deployMethod: string;
};

export type SystemInfoData = {
  serverVersion: string;
  deployedAt: string;
  apiP95Ms: number | null;
  apiAvgMs: number | null;
  apiP99Ms: number | null;
  uptimePercent: number | null;
  uptimeDays: number;
  uptimeIncidentMinutes: number | null;
  host: SystemInfoHost;
  components: SystemInfoComponent[];
};

export const systemInfoApi = {
  get: (): Promise<SystemInfoData> =>
    api.get<SystemInfoData>('/admin/api/system/info'),
};
```

> SystemInfoController uses `ApiResponse<>` envelope — `api.get`.

- [ ] **Step 3: Write monthlyReport.ts**

```typescript
export const monthlyReportApi = {
  download: async (from: string, to: string): Promise<Blob> => {
    const url = `/admin/api/audit/chain/monthly-report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
    const res = await fetch(url, { credentials: 'include' });
    if (!res.ok) {
      throw new Error(`PDF download failed (${res.status})`);
    }
    return res.blob();
  },
};
```

> Native fetch, not `api.*` — endpoint returns binary, not JSON.

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```
Expected: zero errors in adapters (consumer errors in MdsStatusTab/SystemInfoTab/AuditChainPage handled in Tasks 8/9/10).

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/api/mdsStatus.ts admin-ui/src/api/systemInfo.ts admin-ui/src/api/monthlyReport.ts
```

Codex prompt: "codex review F4 adapters (mdsStatus extended + systemInfo + monthlyReport). Verify: (1) MdsStatus 4 new fields including nested successRate30d, (2) MdsHistoryRow shape matches backend MdsHistoryView, (3) SystemInfoData mirrors backend SystemInfoView with nullable fields for p95/avg/p99/uptimePercent/incident, (4) monthlyReport uses native fetch with credentials:'include' and returns Blob, (5) mdsStatus and systemInfo use api.get (envelope-aware) since their controllers use ApiResponse<>. Must-fix only."

```bash
git commit -m "feat(admin-ui): adapters for MDS extended + systemInfo + monthlyReport (Gap #14/#16/#17/#18/#19)"
```

---

## Task 8: MdsStatusTab — drop hardcoded constants + use server fields

**Files:**
- Modify: `admin-ui/src/pages/settings/MdsStatusTab.tsx`

## CRITICAL: ZERO design changes

JSX trees, MetricCard/KvLine components, surrounding cards/tables — all stay byte-identical. Only expressions inside `{...}` and state hooks change.

- [ ] **Step 1: Inspect**

```bash
cat admin-ui/src/pages/settings/MdsStatusTab.tsx
```

Identify:
- `const trustAnchors = 287` hardcoded (~line 109)
- `const syncHistoryFixture = [...]` inline (~lines 34-40)
- KvLine `next 갱신 예정` / `동기화 성공률 (30d)` / `현재 사용 중인 신뢰 모드` (~lines 145-148)
- 표 iterating `syncHistoryFixture` (~lines 167-176)

- [ ] **Step 2: Replace constants with state**

Remove `syncHistoryFixture` inline. Add:

```typescript
const [history, setHistory] = useState<MdsHistoryRow[]>([]);

useEffect(() => {
  mdsStatusApi.history(5).then(setHistory).catch(() => setHistory([]));
}, []);
```

Import `MdsHistoryRow`:
```typescript
import { mdsStatusApi, type MdsStatus, type MdsHistoryRow } from '@/api/mdsStatus';
```

Replace `const trustAnchors = 287` with derivation from server status:
```typescript
const trustAnchors = status?.trustAnchorCount ?? 0;
```

Replace KV expressions:
- `next 갱신 예정` value: `status?.nextUpdate ? `${status.nextUpdate} 03:00 KST` : '알 수 없음'` — already in spec
- `동기화 성공률 (30d)`: read from `status?.successRate30d ?? { ok: 0, total: 0 }`, render as `<><span style={{fontWeight:600}}>{rate.ok} / {rate.total}</span> · <span className="badge badge--success">{rate.total > 0 ? Math.round(rate.ok/rate.total*100) : 0}%</span></>`
- `현재 사용 중인 신뢰 모드`: `<span className="badge badge--info">{status?.trustMode ?? 'MDS_STRICT_OPTIONAL'}</span>`

Replace `syncHistoryFixture.map(...)` with `history.map(...)`. Update field accesses to match `MdsHistoryRow`:
- `r.ts` → `r.startedAt`
- `r.ver` → `r.version ? \`${r.version} · MDS\` : '—'`
- `r.changes` → `r.changeSummary ?? '—'`
- `r.ok` → `r.status === 'SYNCED'` (badge color logic)
- `r.ms` → `r.durationMs ?? '—'`

> JSX structure (rows/cells/badge classNames) byte-identical.

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/settings/MdsStatusTab.tsx
```

Codex prompt: "codex review MdsStatusTab F4 wiring. CRITICAL: verify ZERO design changes — JSX/className/MetricCard/KvLine/table structure byte-identical. trustAnchors hardcoded 287 → status.trustAnchorCount, syncHistoryFixture inline → state via mdsStatusApi.history, 3 KV expressions replaced. Field name mapping (r.ts→r.startedAt, r.ver→version, r.changes→changeSummary, r.ok→status==='SYNCED', r.ms→durationMs) correct. Must-fix only."

```bash
git commit -m "feat(admin-ui): MdsStatusTab uses server fields + history (Gap #16/#17/#18)"
```

---

## Task 9: SystemInfoTab — fixture → adapter

**Files:**
- Modify: `admin-ui/src/pages/settings/SystemInfoTab.tsx`
- Delete: `admin-ui/src/fixtures/systemInfo.ts`

## CRITICAL: ZERO design changes

Cards/grid/MetricCard/KvLine/ComponentRow byte-identical.

- [ ] **Step 1: Inspect**

```bash
cat admin-ui/src/pages/settings/SystemInfoTab.tsx
```

Identify `const info = systemInfoFixture;` (line ~48).

- [ ] **Step 2: Replace with state + adapter**

```typescript
import { useEffect, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { systemInfoApi, type SystemInfoData, type SystemInfoComponent } from '@/api/systemInfo';

// remove: import { systemInfoFixture, type ComponentInfo } from '@/fixtures/systemInfo';
// rename `ComponentInfo` references to `SystemInfoComponent` if any
```

In the component body, replace:
```typescript
export default function SystemInfoTab() {
  const info = systemInfoFixture;
  return ( ... );
}
```

with:
```typescript
export default function SystemInfoTab() {
  const [info, setInfo] = useState<SystemInfoData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    systemInfoApi.get()
      .then(setInfo)
      .catch(() => setInfo(null))
      .finally(() => setLoading(false));
  }, []);

  if (loading || !info) {
    return <div className="page"><div style={{ padding: 40, color: 'var(--text-mute)' }}>System Info 로딩 중…</div></div>;
  }

  return ( ... );  // existing JSX unchanged
}
```

Replace metric card value expressions for nullable fields (`apiP95Ms`, `apiAvgMs`, `apiP99Ms`, `uptimePercent`, `uptimeIncidentMinutes`):
- `${info.apiP95Ms}ms` → `${info.apiP95Ms ?? '—'}ms` (or similar `--`)
- `${info.uptimePercent}%` → `${info.uptimePercent ?? '—'}%`
- etc.

- [ ] **Step 3: Delete fixture**

```bash
grep -rn "fixtures/systemInfo\|systemInfoFixture\|\\bComponentInfo\\b" admin-ui/src/
git rm admin-ui/src/fixtures/systemInfo.ts
```

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/pages/settings/SystemInfoTab.tsx
git status --short  # should show D fixtures/systemInfo.ts
```

Codex prompt: "codex review SystemInfoTab F4 wiring + fixture deletion. CRITICAL: verify ZERO design changes (grid-3/grid-2 cards/MetricCard/ComponentRow byte-identical). useEffect + state loading pattern, nullable handling for p95/avg/p99/uptimePercent/uptimeIncidentMinutes. ComponentInfo type ref renamed to SystemInfoComponent if used. Zero residual fixture references. Must-fix only."

```bash
git commit -m "feat(admin-ui): SystemInfoTab uses real adapter; fixture deleted (Gap #19)"
```

---

## Task 10: AuditChainPage — handleGenerate → real download

**Files:**
- Modify: `admin-ui/src/pages/AuditChainPage.tsx`

## CRITICAL: ZERO design changes

- [ ] **Step 1: Inspect**

Find `handleGenerate` (around lines 94-97):
```typescript
function handleGenerate() {
  window.alert('PDF 생성 기능은 준비 중입니다 (v1.1). 생성에 약 30초 소요 예정.');
  onClose();
}
```

- [ ] **Step 2: Replace with real download trigger**

Add import:
```typescript
import { monthlyReportApi } from '@/api/monthlyReport';
```

If the page already has a `toast` hook (`useToast`), use it for error feedback. If not, fall back to `alert` only for failure.

Replace `handleGenerate`:
```typescript
async function handleGenerate() {
  try {
    const blob = await monthlyReportApi.download(from, to);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `audit-chain-monthly-${from}-to-${to}.pdf`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    onClose();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    window.alert(`PDF 생성 실패: ${msg}`);  // or toast if available
  }
}
```

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/AuditChainPage.tsx
```

Codex prompt: "codex review AuditChainPage handleGenerate replacement. Verify: (1) window.alert placeholder removed, (2) monthlyReportApi.download → Blob → anchor click + revoke pattern, (3) onClose still called on success, (4) error path informs user (toast or alert). Dialog JSX unchanged. Must-fix only."

```bash
git commit -m "feat(admin-ui): AuditChainPage downloads real monthly PDF (Gap #14)"
```

---

## Task 11: F4 regression + cumulative codex + `--no-ff` merge

**Files:** (none)

- [ ] **Step 1: Backend test suite**

```bash
./gradlew :admin-app:test
```

Pre-existing 20 `*ControllerSecurityTest` failures persist (F1/F2/F3 era). New F4 IT(s): none (per execution policy — manual smoke at Task 11 step 5 covers PDF/SystemInfo/MDS).

If any genuine F4 regression, surface as DONE_WITH_CONCERNS or apply minimal fix `fix(f4): regression in <X>`.

- [ ] **Step 2: TS check**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 3: Cumulative codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review F4 cumulative diff. Focus: (1) V34 mds_sync_history migration safety, (2) MdsAdminController extension does not break existing /status callers, (3) MdsSchedulerService history append is best-effort, (4) SystemInfoView nullable fields render correctly in UI, (5) openhtmltopdf 1.0.10 stable + HTML template injection-safe, (6) MdsStatusTab/SystemInfoTab/AuditChainPage design unchanged. Must-fix only. APPROVED for merge if clean."

Apply must-fix as `fix(f4): codex final review feedback`.

- [ ] **Step 4: Merge `--no-ff` to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-gap-fill-f4 -m "Merge Phase F4 — MDS / SystemInfo / Monthly PDF (Gap #14/#16/#17/#18/#19)"
git log --oneline -5
```

- [ ] **Step 5: Manual smoke checklist for user**

**Settings → MDS:**
- 즉시 갱신 클릭 → MDS sync 실행 + 사이드 이력 표에 새 row 추가
- Trust anchors 카드 = 실 entry 개수 (이전 hardcoded 287 → real count)
- "동기화 성공률 (30d)" KV → 실제 history 행 카운트
- "신뢰 모드" → application config 값 (default `MDS_STRICT_OPTIONAL`)

**Settings → SystemInfo:**
- 서버 버전 → build-info.properties 의 version (gradle springBoot.buildInfo() 자동)
- p95/avg/p99 / uptime% → "—" 표시 (actuator 미설정)
- uptime days → 실제 JVM uptime
- 컴포넌트 표 → admin-app / passkey-app / Oracle DB / MDS scheduler

**AuditChain → 월간 보고서:**
- from/to 선택 → "PDF 생성 → 다운로드" → 실제 PDF 파일 다운로드 (alert placeholder 사라짐)
- audit 탭 또는 DB에서 `MONTHLY_REPORT_GENERATED` 이벤트 확인

## Report

Status, backend test result, tsc, codex verdict, merge SHA, manual smoke checklist (copy of above).

---

## Phase F4 Summary

**What ships:**
- V34 `mds_sync_history` 테이블
- MdsAdminController 응답 확장 + `/history` endpoint + scheduler 가 history append
- SystemInfo 신규 endpoint (BuildProperties + JVM uptime + DB ping)
- Monthly PDF 신규 endpoint (openhtmltopdf)
- 어댑터 3개 (mdsStatus extended, systemInfo new, monthlyReport new)
- MdsStatusTab/SystemInfoTab/AuditChainPage 어댑터 연결 + fixture 삭제
- 새 audit 이벤트 `MONTHLY_REPORT_GENERATED`

**Design impact:** zero.

**Out of F4 scope:** Actuator 도입 (p95/avg/p99 정확화) → 별도 phase.

**Closed gaps:** #14 (월간 PDF), #16 (trust anchors), #17 (sync history), #18 (status KVs), #19 (SystemInfo 전체).

**Next phase:** F5 — no-op 버튼 7건 + cleanup.
