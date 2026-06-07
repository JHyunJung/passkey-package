-- ============================================================
-- V41 — ceremony_event: 등록/인증 ceremony 집계용 경량 이벤트 테이블
--
-- 목적: 개요/Funnel 화면의 등록·인증 성공률은 ceremony begin/finish 카운트가
-- 필요하다. audit_log 는 전역 hash-chain 락(AUDIT_CHAIN_LOCK)으로 모든 append 를
-- 직렬화하므로 고빈도 ceremony 기록에 부적합하다. hash chain 없는 단순 카운트
-- 테이블을 따로 둔다.
--
-- 기록자: passkey-app (APP_RUNTIME) — INSERT.  집계자: admin-app (APP_ADMIN) — SELECT.
-- updated_at: append-only 지만 BaseEntity 정책(모든 엔티티 created_at/updated_at NOT NULL,
--   Phase 8)을 따른다. CeremonyEvent 엔티티가 BaseEntity 를 상속하므로 컬럼이 필요하다.
-- 테이블 소유자는 APP_OWNER(Flyway 실행 스키마)이며 양 런타임 유저에 GRANT.
--
-- VPD: 의도적으로 VPD(DBMS_RLS) 정책을 붙이지 않는다. audit_log 와 동일한 패턴 —
--   테넌트 격리는 DB 커널이 아니라 앱 레벨이 담당한다(admin FunnelService 의 명시적
--   WHERE tenant_id + TenantBoundary.assertCanAccessTenant, passkey-app 은 자기
--   ceremony 의 tenantId 로만 기록). VPD 대상은 CREDENTIAL/API_KEY 뿐이며(V3/V8/V20),
--   ceremony_event 는 무결성 보증이 불필요한 단순 카운트 지표다.
--
-- Idempotency: 객체 생성은 ORA-00955(name already used)/ORA-00942 를 swallow.
-- 패턴: V24/V25/V38(EXCEPTION 가드), V13/V28(런타임 GRANT) 와 동일.
-- ============================================================

-- 테이블 — ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE ceremony_event ('
    || 'id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY, '
    || 'tenant_id RAW(16) NOT NULL, '
    || 'action VARCHAR2(32) NOT NULL, '
    || 'created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 인덱스 (tenant_id, action, created_at) — FunnelService 카운트/일별집계 커버. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_ceremony_event_tenant_action_time '
    || 'ON ceremony_event (tenant_id, action, created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 런타임 GRANT (각 GRANT 를 개별 블록으로 — 멱등·부분 적용 안전)
BEGIN EXECUTE IMMEDIATE 'GRANT INSERT ON ceremony_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT ON ceremony_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT, INSERT ON ceremony_event TO APP_ADMIN';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
