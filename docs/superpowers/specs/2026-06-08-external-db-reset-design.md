# 외부 DB(SE) APP_OWNER reset 설계

**작성일**: 2026-06-08 (검증 후 갱신)
**목표**: 우리 시스템의 이전 init 잔재가 남아있는 외부 **Oracle SE(Standard Edition)** 의 `APP_OWNER` 스키마를, DBeaver 에서 `APP_OWNER` 계정으로 비운다. 그 후 `scripts/init-db-external.sh`(Flyway)가 빈 스키마에 재적용 가능해진다.

## 배경 / 문제

- 대상 DB 는 **Oracle SE** 다. SE 는 **VPD(Virtual Private Database, fine-grained access control)를 지원하지 않는다.** 우리 시스템은 SE 에서 app-level Hibernate `@Filter` 로 테넌트 격리를 하고(`passkey.vpd.enabled=false`), VPD 마이그레이션의 `DBMS_RLS.ADD_POLICY` 는 SE2 가드(ORA-00439 무시)로 그냥 건너뛴다 — 정책이 애초에 안 붙는다.
- 그 SE DB 에 **이전 init 시도의 잔재**(우리 시스템 테이블·`flyway_schema_history` 등)가 소량 남아있다. `scripts/init-db-external.sh` + `bootstrap-external.sql` 은 **빈 APP_OWNER 전제**라, 잔재가 있으면 Flyway validate 가 충돌해 재적용이 안 된다.
- 기존 `scripts/reset-app-owner.sh` 는 **Docker 컨테이너 전용**(`docker exec passkey-oracle` + SYS 세션)이라 외부 DB 에 못 쓴다.
- 대상 외부 DB 제약: **SYSDBA 접근 없음**(APP_OWNER 계정만), 작업 머신에 **sqlplus 없음**, 하지만 **DBeaver 로 APP_OWNER 접속 가능**.

## 검증된 전제 (컨테이너 DB 에서 APP_OWNER 권한 실측)

`bootstrap-external.sql` 이 APP_OWNER 에 부여하는 권한으로, SYSDBA 없이 self-service reset 이 가능함을 컨테이너(외부와 동일 권한)에서 **실제 실행해 확인**했다:

- `user_tables`/`user_sequences`/`user_views`/`user_objects` 로 자기 스키마 객체 조회·DROP 가능 ✅
- `GRANT EXECUTE ON DBMS_RLS TO APP_OWNER` → `user_policies` 의 정책을 `DBMS_RLS.DROP_POLICY(USER, ...)` 로 분리 가능 ✅ (SE 에선 정책이 0건이라 이 루프는 그냥 빈 채로 통과)
- ⚠️ **`DROP CONTEXT APP_CTX` 는 불가** — APP_OWNER 는 `CREATE ANY CONTEXT` 만 있고 `DROP ANY CONTEXT` 가 없어 ORA-01031(insufficient privileges). 따라서 **APP_CTX 컨텍스트는 못 지운다.**
- CTX_PKG **패키지는 DROP 가능**하지만, 시드(R__)가 `CTX_PKG.set_tenant` 를 호출하고 APP_CTX 컨텍스트가 CTX_PKG 를 참조하므로 — **CTX_PKG·APP_CTX 둘 다 보존**하는 게 권한·의존성상 가장 깔끔하다(결정).

## 접근

DBeaver 에 **붙여넣어 실행하는 단일 SQL 스크립트**를 만든다. shell 자동화·sqlplus·SYSDBA 전부 불필요.

### 산출물 1: `scripts/reset-app-owner-external.sql`

기존 `scripts/reset-app-owner.sql`(SYS 기반)을 **APP_OWNER self-service + CTX 보존** 버전으로 변환. 컨테이너에서 검증된 형태:

| SYS 버전 | external 버전 |
|---|---|
| `ALTER SESSION SET CONTAINER` + `as sysdba` | 없음 (APP_OWNER 일반 세션, DBeaver 가 PDB/서비스로 직접 접속) |
| `dba_tables WHERE owner='APP_OWNER'` 등 | `user_tables` / `user_sequences` / `user_views` / `user_objects` |
| `dba_policies WHERE object_owner='APP_OWNER'` | `user_policies` |
| `DBMS_RLS.DROP_POLICY(v_owner, obj, pol)` | `DBMS_RLS.DROP_POLICY(USER, obj, pol)` |
| `DROP TABLE APP_OWNER."x" ...` | `DROP TABLE "x" CASCADE CONSTRAINTS PURGE` (자기 스키마라 한정자 불필요) |
| CTX_PKG DROP + `DROP CONTEXT APP_CTX` | **둘 다 보존** (패키지 DROP 루프에서 `object_name <> 'CTX_PKG'` 제외, 컨텍스트는 DROP 시도 안 함) |
| `WHENEVER SQLERROR EXIT` | 제거 (DBeaver 는 sqlplus 지시어 미지원 — PL/SQL 블록 내 RAISE 로 대체) |

**DROP 범위**:
1. VPD 정책 (`user_policies` → `DROP_POLICY(USER, ...)`) — SE 면 0건, EE 잔재면 분리. `ORA-28102/28104` 만 무시, 그 외 RAISE.
2. 테이블 (`DROP TABLE "x" CASCADE CONSTRAINTS PURGE`) — `flyway_schema_history` 포함.
3. 시퀀스
4. 뷰
5. 패키지/프로시저/함수/트리거/타입 — **CTX_PKG 제외**(보존), SYS_% 제외.
6. 휴지통(`user_recyclebin` → `PURGE TABLE`).

**보존**: CTX_PKG(패키지+바디), APP_CTX(컨텍스트). reset 후 Flyway 시드가 `set_tenant` 를 바로 호출 가능.

**멱등성**: "이미 없음" SQLCODE(-942/-4043/-2289/-28102/-28104)만 무시, 그 외 예상 못 한 오류는 `RAISE`. 끝에 `user_tables` 0 + `user_policies` 0 검증, 아니면 `RAISE_APPLICATION_ERROR(-20099/-20098)`.

> **§3(VPD 정책 재분리)는 만들지 않는다.** SE 는 VPD 미지원이라 Flyway 재적용 시 ADD_POLICY 가 ORA-00439 로 건너뛰어 정책이 안 붙는다 → reset 후 다시 뗄 정책이 없다. (EE 컨테이너용 reset-app-owner.sh 와 다른 점.)

### 산출물 2: `scripts/init-db-external.sh` 에 `SKIP_BOOTSTRAP` 옵션

`init-db-external.sh` 단계 1(sqlplus bootstrap)은 작업 머신에 sqlplus 가 없어 못 돈다. 이미 부트스트랩된 외부 DB 재적용 시 단계 1을 건너뛰는 환경변수 `SKIP_BOOTSTRAP=1` 추가:
- 설정 시: 단계 1(sqlplus + bootstrap-external.sql) 건너뜀, 단계 2(admin-app Flyway)만 실행.
- 미설정(기본): 기존 동작 유지(sqlplus 필요).

또한 단계 2의 admin-app 부팅에 SE 용 환경변수가 들어가야 한다(아래 워크플로 참조): `PASSKEY_VPD_ENABLED=false`.

### 워크플로 (실제 수행 절차)

1. **(DBeaver, APP_OWNER 접속)** 접속 스키마/DB 확인: `SELECT USER, ORA_DATABASE_NAME FROM dual;` — 어느 DB·스키마인지 눈으로 확인.
2. **(DBeaver, APP_OWNER)** `reset-app-owner-external.sql` 실행 → 테이블·VPD정책(SE 면 0)·시퀀스·뷰·기타 패키지 DROP, CTX_PKG·APP_CTX 보존, 잔존 0 검증.
3. **(작업 머신)** `SKIP_BOOTSTRAP=1 PASSKEY_VPD_ENABLED=false ORA_HOST=... ORA_PORT=... ORA_SERVICE=... PROFILE=qa scripts/init-db-external.sh` — Flyway 만 빈 스키마에 V1~ 마이그레이션 + 시드 적용. CTX_PKG 가 살아있어 시드의 set_tenant 동작. SE2 가드로 VPD 정책은 안 붙음.

CTX_PKG/APP_CTX 보존이므로 "CTX 복구" 단계가 없다 → 2단계로 끝남.

## 안전장치 (dev/qa 전제)

- `reset-app-owner-external.sql` 상단에 **⚠️ 파괴적 경고** + "dev/qa 전용, prod 금지" 명시.
- DBeaver 수동 실행이라 자동 타이핑 가드 불가 → §1 첫 줄에 `SELECT USER, ORA_DATABASE_NAME FROM dual;` 을 두어 실행 전 대상 확인 유도.
- prod 보호: 본 스크립트는 어떤 prod 자격증명도 포함하지 않으며, prod Flyway 는 `clean-disabled: true`.

## 테스트 / 검증

- 컨테이너 DB(APP_OWNER 계정, 외부와 동일 권한)에서 `reset-app-owner-external.sql` 을 `docker exec ... APP_OWNER` 로 실행 → 테이블 0·정책 0 + CTX_PKG VALID·APP_CTX 보존 확인. **(설계 단계에서 이미 실행해 확인함.)**
- init-db-external.sh `SKIP_BOOTSTRAP=1` 으로 Flyway 재적용 → tenant 시드·테이블 복구 확인(컨테이너 기준; 외부 SE 는 사용자가 DBeaver+실 DB 로 최종 확인).

## 범위 밖

- 컨테이너용 `reset-app-owner.sh` 의 VPD 자동 분리 통합(EE 전용, 별도 논의).
- VPD 정책 재분리 §3 (SE 는 정책 미부착이라 불필요).
- prod reset (의도적 미지원).
