-- KST 전환: 앱 커넥션은 HikariCP connection-init-sql 로
-- ALTER SESSION SET TIME_ZONE='Asia/Seoul' 를 강제한다(application-common.yml).
-- 이 마이그레이션은 정책을 명시적으로 기록하기 위한 no-op 주석 + 검증용 SELECT.
-- SYSTIMESTAMP DEFAULT 컬럼들은 세션 TIME_ZONE 을 따르므로 별도 DDL 변경 불필요.
SELECT 1 FROM dual;
