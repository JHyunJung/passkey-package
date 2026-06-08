# 외부 DB APP_OWNER reset 설계

**작성일**: 2026-06-08
**목표**: 이미 데이터/객체가 있는 외부 dev/qa Oracle 의 `APP_OWNER` 스키마를, DBeaver 에서 `APP_OWNER` 계정으로 한 번에 초기화한다. 그 후 `scripts/init-db-external.sh`(Flyway)가 빈 스키마에 재적용 가능해진다.

## 배경 / 문제

- `scripts/init-db-external.sh` + `bootstrap-external.sql` 은 **빈 APP_OWNER 전제**다. 이미 객체/데이터가 있으면 Flyway validate 가 충돌해 재적용이 안 된다.
- 기존 `scripts/reset-app-owner.sh` 는 **Docker 컨테이너 전용**(`docker exec passkey-oracle` + SYS 세션)이라 외부 DB 에 못 쓴다.
- 대상 외부 DB 제약: **SYSDBA 접근 없음**(APP_OWNER 계정만), 작업 머신에 **sqlplus 없음**, 하지만 **DBeaver 로 APP_OWNER 접속 가능**.

## 검증된 전제 (컨테이너 DB 에서 APP_OWNER 권한 확인)

`bootstrap-external.sql` 이 APP_OWNER 에 부여하는 권한으로 SYSDBA 없이 self-service reset 이 가능함을 확인했다:

- `user_objects` 로 자기 스키마 객체 전부 조회·DROP 가능 (TABLE/INDEX/SEQUENCE/VIEW/PACKAGE 등)
- `GRANT EXECUTE ON DBMS_RLS TO APP_OWNER` → `user_policies` 의 VPD 정책을 `DBMS_RLS.DROP_POLICY(USER, ...)` 로 분리 가능
- `GRANT CREATE ANY CONTEXT TO APP_OWNER` → `DROP CONTEXT APP_CTX` 가능
- 따라서 SYS·sqlplus 불필요. DBeaver 의 APP_OWNER 세션만으로 완결.

## 접근

DBeaver 에 **붙여넣어 실행하는 단일 SQL 스크립트**를 만든다. shell 자동화·sqlplus·SYSDBA 전부 불필요.

### 산출물: `scripts/reset-app-owner-external.sql`

기존 `scripts/reset-app-owner.sql`(SYS 기반)을 **APP_OWNER self-service 버전**으로 변환:

| SYS 버전 | external 버전 |
|---|---|
| `ALTER SESSION SET CONTAINER` + `as sysdba` | 없음 (APP_OWNER 일반 세션, DBeaver 가 PDB 로 직접 접속) |
| `dba_objects WHERE owner='APP_OWNER'` | `user_objects` |
| `dba_policies WHERE object_owner='APP_OWNER'` | `user_policies` |
| `DBMS_RLS.DROP_POLICY(v_owner, obj, pol)` | `DBMS_RLS.DROP_POLICY(USER, obj, pol)` |
| `DROP TABLE APP_OWNER."x" ...` | `DROP TABLE "x" ...` (자기 스키마라 한정자 불필요) |
| `WHENEVER SQLERROR EXIT` | 제거 (DBeaver 는 sqlplus 지시어 미지원 — PL/SQL 블록 내 RAISE 로 대체) |

**DROP 범위** (컨테이너 reset 과 동일 — 전부 비움):
1. VPD 정책 (`user_policies` → `DROP_POLICY(USER, ...)`)
2. 테이블 (`DROP TABLE "x" CASCADE CONSTRAINTS PURGE`) — `flyway_schema_history` 포함
3. 시퀀스
4. 뷰
5. 패키지/프로시저/함수/트리거/타입 — **CTX_PKG 포함**
6. `DROP CONTEXT APP_CTX`

**멱등성**: "이미 없음" 류 SQLCODE(-942/-4043/-2289/-28102/-28104)만 무시, 그 외 예상 못 한 오류는 `RAISE`. 끝에 `user_objects`(SYS_*/BIN$* 제외) + `user_policies` 둘 다 0 검증, 아니면 `RAISE_APPLICATION_ERROR`.

### 워크플로 (실제 수행 절차)

CTX_PKG/APP_CTX 를 전부 DROP 하므로(결정 사항), 재적용 전 그것들을 먼저 복구해야 한다. 시드(R__)가 `CTX_PKG.set_tenant` 를 호출하기 때문이다.

1. **(DBeaver, APP_OWNER)** `reset-app-owner-external.sql` 의 §1 (RESET) 실행 → 객체·VPD정책·CTX_PKG·APP_CTX 전부 DROP, 잔존 0 검증.
2. **(DBeaver, APP_OWNER)** `bootstrap-external.sql` 의 CTX_PKG/APP_CTX 생성 블록(L94~115)만 떼어 실행 → CTX_PKG + APP_CTX 복구. (`CREATE OR REPLACE` 라 멱등.)
   - 편의를 위해 이 블록을 `reset-app-owner-external.sql` §2 로 같은 파일에 복제해 둔다(주석으로 "재적용 전 실행" 표시).
3. **(작업 머신)** `scripts/init-db-external.sh` 재실행 — 단 sqlplus 가 없으므로 bootstrap(단계 1)은 건너뛰고 **Flyway(단계 2)만** 돈다. 빈 테이블 스키마에 V1~ 마이그레이션 + 프로필 시드 적용.
   - init-db-external.sh 가 단계 1에서 sqlplus 부재로 막히면, 단계 1을 건너뛰는 플래그(`SKIP_BOOTSTRAP=1`) 또는 안내가 필요 → 본 작업에서 init-db-external.sh 에 `SKIP_BOOTSTRAP` 옵션을 추가한다.
4. **(DBeaver, APP_OWNER, 선택)** 외부 dev/qa 앱이 `vpd.enabled=false` 면, Flyway 가 다시 붙인 VPD 정책 7개가 P001(origins 0행)을 유발한다. `reset-app-owner-external.sql` §3 "VPD 정책 분리 전용" 블록을 한 번 더 실행해 정책을 뗀다.

### 파일 구성: `reset-app-owner-external.sql` 3개 섹션

DBeaver 에서 섹션 단위로 선택 실행할 수 있게 주석으로 명확히 구분:

- **§1 RESET** — APP_OWNER 객체·VPD정책·CTX_PKG·APP_CTX 전부 DROP + 잔존 0 검증
- **§2 CTX_PKG 복구** — `bootstrap-external.sql` 의 CTX_PKG/APP_CTX 생성 블록 복제 (재적용 전 실행)
- **§3 VPD 정책 분리** — Flyway 재적용 후, vpd off 환경에서 P001 방지용 (재사용 가능, 멱등)

## 안전장치 (dev/qa 전제)

- 파일 상단에 **⚠️ 파괴적 경고** + "dev/qa 전용, prod 금지" 명시.
- DBeaver 수동 실행이라 자동 가드(타이핑 확인)는 불가 → 대신 §1 첫 줄에 접속 스키마 확인 쿼리(`SELECT USER, ORA_DATABASE_NAME FROM dual`)를 두어, 실행 전 사용자가 **어느 DB·어느 스키마에 붙어있는지 눈으로 확인**하도록 유도.
- prod 보호: 본 스크립트는 어떤 prod 자격증명도 포함하지 않으며, prod Flyway 는 `clean-disabled: true`.

## init-db-external.sh 변경 (최소)

- `SKIP_BOOTSTRAP=1` 환경변수 추가: 설정 시 단계 1(sqlplus bootstrap)을 건너뛰고 단계 2(Flyway)만 실행. 이미 부트스트랩된(또는 DBeaver 로 CTX_PKG 복구한) 외부 DB 재적용 시 sqlplus 부재로 막히지 않게 한다.

## 테스트 / 검증

- 컨테이너 DB(APP_OWNER 계정, 외부와 동일 권한)에서 `reset-app-owner-external.sql` §1~§3 을 sqlplus 대신 `docker exec ... APP_OWNER` 로 실행해 검증:
  - §1 후 `user_objects`·`user_policies` 0 확인
  - §2 후 CTX_PKG VALID + APP_CTX 존재 확인
  - init-db-external.sh `SKIP_BOOTSTRAP=1` 으로 Flyway 재적용 → tenant 시드·테이블 복구 확인
  - §3 후 `user_policies` 0 확인
- DBeaver 자체 실행은 사용자가 외부 DB 에서 최종 확인(작업 머신에 sqlplus 없음).

## 범위 밖

- 컨테이너용 `reset-app-owner.sh` 의 VPD 자동 분리 통합(앞서 별도 논의됨 — 본 spec 은 외부 DB 전용).
- prod reset (의도적으로 미지원).
