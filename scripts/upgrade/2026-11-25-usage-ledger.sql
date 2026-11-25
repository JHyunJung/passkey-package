-- 사용량 정산 장부: usage_daily / usage_monthly / usage_period_close 추가.
-- PSK_APP_OWNER 로 실행한다. 재실행해도 안전하다(테이블이 이미 있으면 건너뛰고, GRANT 는 매번 실행).
-- 기준 스키마(scripts/full-schema-part2-schema.sql)와 같은 정의를 유지할 것.
SET SERVEROUTPUT ON

DECLARE
    PROCEDURE create_if_missing(p_table VARCHAR2, p_ddl VARCHAR2) IS
        n NUMBER;
    BEGIN
        SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = UPPER(p_table);
        IF n = 0 THEN
            EXECUTE IMMEDIATE p_ddl;
            DBMS_OUTPUT.PUT_LINE('created ' || p_table);
        ELSE
            DBMS_OUTPUT.PUT_LINE('skip (exists) ' || p_table);
        END IF;
    END;
BEGIN
    create_if_missing('usage_daily', 'CREATE TABLE usage_daily (
        tenant_id     RAW(16)                     NOT NULL,
        usage_date    DATE                        NOT NULL,
        reg_success   NUMBER(12)   DEFAULT 0      NOT NULL,
        auth_success  NUMBER(12)   DEFAULT 0      NOT NULL,
        reg_attempt   NUMBER(12)   DEFAULT 0      NOT NULL,
        auth_attempt  NUMBER(12)   DEFAULT 0      NOT NULL,
        basis         VARCHAR2(8)                 NOT NULL,
        aggregated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        CONSTRAINT ck_usage_daily_basis CHECK (basis IN (''LEDGER'',''LEGACY'')),
        CONSTRAINT pk_usage_daily PRIMARY KEY (tenant_id, usage_date)
    )');
    create_if_missing('usage_monthly', 'CREATE TABLE usage_monthly (
        tenant_id     RAW(16)      NOT NULL,
        period        VARCHAR2(7)  NOT NULL,
        reg_success   NUMBER(14)   NOT NULL,
        auth_success  NUMBER(14)   NOT NULL,
        reg_attempt   NUMBER(14)   NOT NULL,
        auth_attempt  NUMBER(14)   NOT NULL,
        CONSTRAINT pk_usage_monthly PRIMARY KEY (tenant_id, period)
    )');
    create_if_missing('usage_period_close', 'CREATE TABLE usage_period_close (
        period        VARCHAR2(7)                 NOT NULL,
        status        VARCHAR2(8)                 NOT NULL,
        basis         VARCHAR2(8)                 NOT NULL,
        reg_success   NUMBER(14)                  NOT NULL,
        auth_success  NUMBER(14)                  NOT NULL,
        reg_attempt   NUMBER(14)                  NOT NULL,
        auth_attempt  NUMBER(14)                  NOT NULL,
        tenant_count  NUMBER(8)                   NOT NULL,
        closed_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        CONSTRAINT ck_usage_close_status CHECK (status IN (''CLOSED'',''PARTIAL'')),
        CONSTRAINT ck_usage_close_basis  CHECK (basis IN (''LEDGER'',''LEGACY'')),
        CONSTRAINT pk_usage_period_close PRIMARY KEY (period)
    )');
END;
/

GRANT SELECT ON usage_daily TO PSK_APP_ADMIN;
GRANT INSERT ON usage_daily TO PSK_APP_ADMIN;
GRANT UPDATE ON usage_daily TO PSK_APP_ADMIN;
GRANT SELECT ON usage_daily TO PSK_APP_RUNTIME;
GRANT SELECT ON usage_monthly TO PSK_APP_ADMIN;
GRANT INSERT ON usage_monthly TO PSK_APP_ADMIN;
GRANT SELECT ON usage_monthly TO PSK_APP_RUNTIME;
GRANT SELECT ON usage_period_close TO PSK_APP_ADMIN;
GRANT INSERT ON usage_period_close TO PSK_APP_ADMIN;
GRANT SELECT ON usage_period_close TO PSK_APP_RUNTIME;
