# VPD 제거 배포 runbook

Oracle VPD(Virtual Private Database / DBMS_RLS) 멀티테넌트 격리를 코드/설정/DB
전 계층에서 완전히 제거한 변경의 배포 절차입니다. 테넌트 격리는 이제 앱 레벨
Hibernate `@Filter`(`TenantFilterAspect`)가 전담합니다.

## 영향

V3/V8/V19/V20/V35/V42 마이그레이션 파일의 내용이 no-op(또는 명시 tenant_id 방식)으로
재작성되어 **Flyway 체크섬이 변경**되었습니다. `flyway.validate-on-migrate` 가 켜진
prod/qa 의 기배포 DB 에서는 기록된 체크섬과 새 파일 체크섬이 어긋나 마이그레이션이
실패하므로 `flyway repair` 가 필요합니다.

신규 객체로 `V52__drop_vpd.sql` 이 추가되어, 기배포 DB 에 남아 있는 실제 VPD 객체를
forward-only 로 제거합니다.

## 절차 (기배포 EE/XE DB — VPD 정책이 실제 존재)

1. 앱 중단 또는 롤링 배포 준비.
2. `flyway repair` — 재작성된 V3/V8/V19/V20/V35/V42 의 기록 체크섬을 새 파일
   체크섬으로 재정렬한다.
3. `flyway migrate` — `V52__drop_vpd.sql` 이 7개 정책 + `tenant_predicate` 함수 +
   `api_key_lookup_pkg` + `CTX_PKG` 패키지 + `APP_CTX` 컨텍스트를 제거한다.
4. 검증 쿼리 (APP_OWNER 또는 SYS):
   - `SELECT COUNT(*) FROM user_policies;` → **0** (VPD 정책 0)
   - `SELECT object_name FROM user_objects WHERE object_name IN ('TENANT_PREDICATE','API_KEY_LOOKUP_PKG','CTX_PKG');` → **0행**
   - `SELECT COUNT(*) FROM all_context WHERE namespace='APP_CTX';` → **0** 이면 완전 제거.
     **1** 이면 APP_OWNER 에 `DROP CONTEXT` 권한이 없어 빈 컨텍스트가 잔존하는 것이다
     (무해 — 참조하던 `CTX_PKG` 패키지가 제거돼 동작 불가 상태로만 남는다). 이 경우
     V52 로그에 `[V52][WARN]` 가 출력된다. 완전 제거하려면 SYSDBA 로
     `DROP CONTEXT APP_CTX;` 를 수동 실행한다.
5. 신규 앱 배포. 배포 설정에 `PASSKEY_VPD_ENABLED` 환경변수가 있으면 제거한다
   (앱이 더 이상 읽지 않음).

## 절차 (SE2 DB — VPD 미지원, 정책 애초에 없음)

- `flyway repair` 후 `flyway migrate` 만 수행한다. V52 의 모든 DROP 은
  "객체 없음"(ORA-28101 / ORA-04043) 또는 "FGAC 미지원"(ORA-00439)으로 no-op 처리된다.
- bootstrap 은 더 이상 VPD 인프라(DBMS_RLS GRANT / CTX_PKG / APP_CTX)를 만들지
  않는다(`bootstrap-schema.sql` / `bootstrap-external-body.sql`).

## 절차 (신규 DB)

- 일반 `flyway migrate` 만 수행한다. 재작성된 V3~V42(no-op) 와 V52(뗄 객체 없음)가
  전부 clean 하게 통과한다. 스키마/유저는 `bootstrap-schema.sql`(컨테이너) 또는
  `bootstrap-external.sql`(외부 Oracle) 로 생성한다.

## 롤백

- 코드 롤백은 가능하다. DB 는 forward-only — VPD 정책은 자동으로 재생성되지 않는다.
  격리는 앱 레벨 `@Filter` 가 계속 보장하므로 **정책 부재는 보안 회귀가 아니다**.
- 코드를 이전 버전으로 되돌려도 앱이 VPD 컨텍스트를 set 하지 않고 native 쿼리 경로를
  쓰므로(ApiKeyLookupService) 정상 동작한다.

## 비고

- `bootstrap-vpd.sql` → `bootstrap-schema.sql` 로 리네임되었다. 운영 프로비저닝
  스크립트/문서가 옛 이름을 참조하면 갱신이 필요하다. (외부 DB 경로는
  `bootstrap-external.sql` / `bootstrap-external-body.sql`.)
- DB 유저 3분할(APP_OWNER / APP_RUNTIME_USER / APP_ADMIN_USER)은 그대로 유지한다
  (최소권한 원칙). VPD 제거는 격리 메커니즘만 앱 레벨로 옮긴 것이고 권한 분리는
  보존된다.
- 기존 외부 SE DB 를 비우고 재적용하려면 `scripts/reset-app-owner-external.sql`
  (DBeaver, APP_OWNER 세션)을 쓴다 — VPD 잔재(정책/CTX_PKG)도 함께 청소한다.
