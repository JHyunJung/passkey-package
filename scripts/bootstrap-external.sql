-- ============================================================
-- bootstrap-external.sql — DBeaver/수동 실행용 wrapper.
-- ⚠️ DBeaver 등 DEFINE 미지원 클라이언트: 아래 DEFINE 3줄을 강한 실제 비번으로
--    직접 치환한 뒤 실행하라. 빈 채로 실행하면 body 의 가드가 fail-closed.
-- sqlplus / init-db-external.sh 경로는 이 wrapper 를 거치지 않고 body 를
--    직접 호출하며 env 값을 DEFINE 으로 주입한다.
-- ============================================================
DEFINE app_owner_pw = ""
DEFINE runtime_pw   = ""
DEFINE admin_pw     = ""
-- 서비스/PDB 명: DBeaver 수동 실행 시 실제 PDB 명으로 변경. 기본값 XEPDB1.
DEFINE ora_service  = XEPDB1

@@bootstrap-external-body.sql
