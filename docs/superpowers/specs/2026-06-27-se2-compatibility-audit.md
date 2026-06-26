# Oracle SE2 정적 호환성 감사 보고서

- **작성일**: 2026-06-27
- **대상**: Passkey2 (core / admin-app / passkey-app, Flyway V1~V52)
- **현재 환경**: dev/test = `gvenzl/oracle-xe:21-slim-faststart` (Oracle XE 21c)
- **검증 목적**: 운영 DB가 **Oracle SE2(Standard Edition 2)** 로 갈 예정이므로, 컨테이너 없이 코드·스키마·마이그레이션·부트스트랩이 SE2에서 정상 작동할지 **정적으로** 사전 검증
- **방법**: 4개 감사 영역 병렬 정적 분석(grep + 파일 정독) + Oracle 공식 라이선스 문서 교차 근거

---

## Executive Summary

> ## 🟢 결론: 코드베이스는 Oracle SE2 호환으로 판정된다. 🔴 비호환(blocker) 발견 0건.

가장 결정적인 근거: **이 프로젝트가 실제로 사용했던 유일한 EE 전용 기능은 VPD(DBMS_RLS)였고, 그것이 이미 전면 제거되어 테넌트 격리가 앱 레벨 Hibernate `@Filter` 단독으로 동작한다.** (main 머지 `f60f3fe`, 2026-06-24)

| 영역 | 판정 | 🔴 | 🟡 |
|---|---|---|---|
| 1. 스키마/마이그레이션 (V1~V52) | 🟢 호환 | 0 | 2 |
| 2. 계정/권한 부트스트랩 | 🟢 호환 | 0 | 2 |
| 3. XE 고유 제약 역의존 | 🟢 중립 | 0 | 0 |
| 4. 런타임 애플 면 (Hibernate/JDBC) | 🟢 중립 | 0 | 1 |

🟡(주의)는 전부 **무해하거나 배포 절차로 흡수 가능한 항목**이며, 코드 수정 없이 운영 가능. 단 아래 "정적 검증의 한계"에 따라 **실제 SE2 인스턴스에서 Flyway 1회 적용 + 핵심 IT 통과를 권장**한다(정적 분석 100% 보증 아님).

---

## 근거: SE2 vs EE 기능 가용성 (Oracle 공식)

출처: Oracle Database 19c *Licensing Information User Manual* (Table 1-6 / 1-8 / 1-10 / 1-13).

| 기능 | SE2 | EE | 이 프로젝트 사용? |
|---|:---:|:---:|---|
| **Virtual Private Database (VPD) / DBMS_RLS / FGAC** | ❌ N | ✅ Y | **과거 사용 → 전면 제거됨** |
| Partitioning | ❌ N | ✅ Y (옵션) | 미사용 |
| Advanced Compression | ❌ N | ✅ Y (옵션) | 미사용 |
| Database In-Memory | ❌ N | ✅ Y (옵션) | 미사용 |
| Diagnostic / Tuning Pack | ❌ N | ✅ Y (옵션) | 미사용 |

> **핵심**: SE2가 못 쓰는 EE 전용 기능 5종 중, 이 프로젝트가 실제로 의존했던 것은 **VPD 하나뿐**이었고 이미 제거됐다. 나머지 4종은 애초에 사용 이력이 없다. 따라서 에디션 다운그레이드(XE→SE2)로 깨질 표면이 사실상 VPD 잔재 처리에 한정된다.

참고: SE2는 무료 XE와 달리 정식 지원(보안 패치 포함) 에디션이며, 소켓 2개·CPU 제약이 있으나 XE의 RAM/데이터 한도(11~12GB)보다 넉넉하다. 즉 XE→SE2는 대부분 **제약 완화** 방향이라 리소스 측 호환 리스크는 낮다.

---

## 영역 1 — 스키마 / 마이그레이션 호환성 (핵심)

대상: `core/src/main/resources/db/migration/` V1~V52 (52개)

| 검사항목 | 발견 | 대표 근거 | 판정 |
|---|---|---|:---:|
| EE 전용 기능 (파티셔닝/패러렐/압축/INMEMORY/bitmap join idx/MV rewrite/PDB/튜닝팩) | 0건 | 전수 grep 0 | 🟢 |
| DBMS_RLS **신규 정책 생성** | 0건 | `ADD_POLICY` 없음 | 🟢 |
| DBMS_RLS **제거 안전성** | V52 | `V52__drop_vpd.sql:40-55` EXECUTE IMMEDIATE + ORA-00439/-28101 swallow | 🟢 |
| VPD no-op 폐기 파일 | V3/V8/V20/V35/V42 | `SELECT 1 FROM dual` (주석만) | 🟢 |
| `IS JSON` 제약 | 11건 | `V6:19`, `V2:25` 외 | 🟢 12c+ 표준, SE2 지원 |
| SEQUENCE / SYS_GUID | 17 / 11건 | `V19`, `V26` 외 | 🟢 |
| PL/SQL 패키지 (AUTHID DEFINER) | 2개 | `V18:23`, `V19:333` | 🟢 |
| B-tree / 함수기반 UNIQUE 인덱스 | 40+ / 3건 | `V15`, `V19`, `V50` | 🟢 |
| TIMESTAMP WITH TIME ZONE / CLOB / BLOB / CHECK / FK | 전수 | — | 🟢 |

### 🟡 주의 1-A — 기배포 EE → SE2 전환 시 V52의 ORA-01031
`V52__drop_vpd.sql`이 `DROP CONTEXT APP_CTX`를 시도할 때 APP_OWNER에 `DROP ANY CONTEXT` 권한이 없어 ORA-01031이 날 수 있다. **스크립트가 이미 ORA-01031을 관용하고 경고만 로그**하므로 무해(참조 패키지 CTX_PKG는 이미 제거되어 APP_CTX는 빈 객체). 완전 정리는 SYSDBA가 수동 `DROP CONTEXT APP_CTX`.

### 🟡 주의 1-B — V44 데이터손실 가드의 이론적 우회
`V44`의 `SELECT COUNT(*) FROM credential` 데이터손실 가드는, **기배포 EE DB에 VPD 정책이 남아 있고 + APP_CTX가 unset인 경우**에만 술어 UNKNOWN으로 0행을 보여 우회될 수 있다. **신규 SE2 배포는 V52가 VPD를 완전 제거하므로 이 경로 자체가 발생 불가.** 기배포 EE→SE2 전환 시에는 V52가 V44보다 먼저(낮은 버전이므로 자연히) 실행됨을 확인.

**영역 1 결론: 🟢 SE2 완전 호환.**

---

## 영역 2 — 계정 / 권한 부트스트랩

대상: `scripts/bootstrap-*.sql`, `scripts/reset-app-owner*.sql`, `scripts/init-*.sh`, `run-bootstrap.sh`, `docker-compose.yml`, GRANT 포함 마이그레이션

| 검사항목 | 근거 | 판정 |
|---|---|:---:|
| APP_OWNER 생성 (외부 경로) | `bootstrap-external-body.sql:28-35` 표준 CREATE USER | 🟢 |
| APP_RUNTIME / APP_ADMIN 생성 | `bootstrap-schema.sql:70-88` 멱등 EXCEPTION 가드 | 🟢 |
| 권한 부여 | 전부 표준 객체권한(SELECT/INSERT/...) + 스키마 소유자 권한(CREATE TABLE/SEQUENCE/...). **시스템 권한(CREATE ANY, EXECUTE ON DBMS_RLS) 의존 0** | 🟢 |
| DBMS_RLS / EXEMPT ACCESS POLICY 부여 | 부트스트랩에서 제거됨 | 🟢 |
| APP_OWNER 자동생성(gvenzl `APP_USER` env) | `docker-compose.yml:7` — **gvenzl 전용**, 운영 SE2엔 없음 | 🟡 |
| 서비스명 `XEPDB1` 하드코드 | `bootstrap-schema.sql:21`, `run-bootstrap.sh:7`, `reset-app-owner.sql:20` | 🟡 |
| DROP CONTEXT 권한 | `V52` ORA-01031 경고만 (= 주의 1-A) | 🟡 |

### 🟡 주의 2-A — XEPDB1 서비스명 하드코드
로컬 dev 경로(`bootstrap-schema.sql`, `run-bootstrap.sh`, `reset-app-owner.sql`)에 `XEPDB1`이 고정. 운영 SE2의 PDB/서비스명이 다르면 연결 실패. **단 외부 배포 경로 `init-db-external.sh`는 `ORA_SERVICE` 환경변수로 매개변수화되어 있음** → 운영은 이 경로를 쓰면 됨. (선택 개선: 로컬 스크립트도 `${ORA_SERVICE:-XEPDB1}`로 매개변수화)

### 🟡 주의 2-B — gvenzl 자동생성 부재
운영 SE2는 gvenzl의 `APP_USER` 자동 계정 생성이 없으므로, DBA가 APP_OWNER를 수동 생성하거나 `bootstrap-external.sql`을 SYSDBA로 1회 실행해야 한다(아래 운영 체크리스트 참조).

**영역 2 결론: 🟢 SE2 호환. 운영 시 계정 생성·서비스명만 수동/환경변수로 처리.**

---

## 영역 3 — XE 고유 제약 역의존 점검

| 검사항목 | 근거 | 판정 |
|---|---|:---:|
| 커넥션풀/세션 (prod 10/2, test 4/1) | `application-common.yml:34-35` | 🟢 SE2가 더 큰 풀 수용, 에디션 중립 |
| 타임존 (KST `Asia/Seoul`) | `application-common.yml:12,37` JDBC + Hibernate 양쪽 명시 | 🟢 세션 TIME_ZONE은 에디션 무관 |
| NLS/캐릭터셋 | 명시 NLS_LANG 없음, ojdbc11+orai18n 협상, SQL은 ASCII | 🟢 |
| JDBC URL/서비스명 | prod는 `spring.datasource.url` 환경변수 주입(테스트만 XEPDB1 고정) | 🟢 |
| TABLESPACE/STORAGE/INITIAL/PCTFREE 절 | 전수 0건 | 🟢 SE2 디폴트 사용 |

**영역 3 결론: 🟢 SE2 중립. XE 전용 하드코딩 없음, 모든 제약 환경변수 주입.**

---

## 영역 4 — 런타임 애플 면 (Hibernate / JDBC)

| 검사항목 | 근거 | 판정 |
|---|---|:---:|
| Hibernate Dialect (`OracleDialect`, Hibernate 6.6) | `application-common.yml:11` | 🟢 전 버전 지원 |
| 테넌트 격리 = 앱 레벨 `@Filter` | `TenantFilterAspect.java:50-68` `Session.enableFilter()` — VPD/SYS_CONTEXT 의존 0 | 🟢 |
| EE 의존 SQL 힌트 (`/*+ PARALLEL */` 등) | 0건 | 🟢 |
| `ROWNUM <= :batchSize` 배치 삭제 (5개 repo) | `CeremonyEventRepository:57` 외 | 🟡→🟢 ROWNUM은 SE2 표준 |
| `TRUNC(date)` 일자 버킷팅 (2곳) | Audit/Ceremony Repository | 🟢 |
| `FETCH FIRST n ROWS ONLY` | `AuditLogRepository`, `MdsHistoryService:38` | 🟢 12c+ 표준 |
| SEQUENCE NEXTVAL / UUID↔RAW(16) / OffsetDateTime↔TSTZ | 다수 | 🟢 Hibernate 변환, 에디션 무관 |
| raw JDBC (CallableStatement PL/SQL, JdbcTemplate) | `SigningKeyProvider.java:126`, `MdsHistoryService` | 🟢 표준 JDBC API |
| `v$version` SELECT | `SystemInfoService.java:50-54` | 🟡 |

### 🟡 주의 4-A — `v$version` SELECT 권한
관리 대시보드가 `SELECT banner FROM v$version`으로 DB 배너를 표시하나, **try-catch로 보호**되어 권한 없으면 "Oracle (unknown)"으로 폴백 → 무해. 배너를 띄우려면(선택) APP_ADMIN_USER에 `GRANT SELECT ON v$version` 부여.

**영역 4 결론: 🟢 SE2 중립. 모든 SQL/ORM이 에디션 무관.**

---

## 정적 검증의 한계 (실제 SE2 런타임에서만 확정 가능)

정적 분석은 "EE 전용 기능을 쓰지 않는다"는 **부재 증명**에 강하지만, 다음은 실제 SE2 인스턴스에서만 확정된다:

1. **Flyway V1~V52 전체 적용** — 실제 SE2에서 한 번 끝까지 돌려 체크섬·DDL 성공 확인 (특히 V52 동적 SQL의 SQLCODE 실제 반응).
2. **권한 GRANT 실제 성공** — `UNLIMITED TABLESPACE`, `CREATE TRIGGER` 등이 해당 SE2 PDB의 정책상 부여되는지.
3. **앱 부팅 + 핵심 IT** — admin-app/passkey-app가 SE2 연결로 부팅되고 테넌트 격리(@Filter)가 실제 동작하는지.
4. **리소스/성능** — SE2의 CPU/메모리 제약, 사용자 데이터 한도(~12GB) 하의 부하는 별도 동적 검증 영역.

> ⚠️ 본 보고서는 **정적 감사 1회 산출물**이며 SE2 컨테이너 동적 검증은 범위 밖이다. SE2 이미지(container-registry.oracle.com pull 또는 oracle/docker-images 자체 빌드) 확보 시 위 4항목을 1회 실증하면 호환성이 100% 확정된다.

---

## 운영 SE2 배포 체크리스트

부트스트랩(DBA, 1회):
- [ ] APP_OWNER 계정 생성 — DBA가 직접 `CREATE USER` 하거나 `bootstrap-external.sql`을 SYSDBA로 실행 (gvenzl 자동생성 없음)
- [ ] APP_RUNTIME / APP_ADMIN 계정 + 권한 부여 (bootstrap 스크립트가 멱등 처리)
- [ ] (선택) APP_ADMIN_USER에 `GRANT SELECT ON v$version` — 대시보드 배너용

마이그레이션 적용:
- [ ] `init-db-external.sh` 사용 + `ORA_SERVICE=<운영 PDB명>` 지정 (XEPDB1 하드코드 경로 회피)
- [ ] `PASSKEY_VPD_ENABLED=false` 확인 (SE2는 VPD 미지원이므로 필수)
- [ ] V52 로그의 ORA-01031/ORA-00439 경고는 **정상** — 무시 가능
- [ ] (기배포 EE→SE2 전환 시) `flyway repair` 후 적용, V52가 V44보다 먼저 도는지 확인

런타임 설정:
- [ ] `spring.datasource.url` = SE2 인스턴스 (환경변수)
- [ ] JVM `-Duser.timezone=Asia/Seoul` + `jdbc.time_zone=Asia/Seoul` (KST 마이그레이션 전제, raw-JDBC bridge 2곳 때문에 필수)

권장(범위 밖, 다음 단계):
- [ ] 실제 SE2 컨테이너로 Flyway 전체 적용 + 핵심 IT 1회 실증 (정적 한계 4항목 해소)

---

## 부록 — 선택 개선 백로그 (호환성 blocker 아님)

코드 수정 없이도 운영 가능하나, 향후 다듬으면 좋은 항목:

1. 로컬 부트스트랩 스크립트의 `XEPDB1` → `${ORA_SERVICE:-XEPDB1}` 매개변수화 (주의 2-A).
2. V52 `DROP CONTEXT` 실패를 더 명시적인 메시지로 (현재 경고 로그로 충분).
3. SE2 전용 운영 가이드 문서화 (`PASSKEY_VPD_ENABLED=false` 기본값, 서비스명, v$version 권한).
