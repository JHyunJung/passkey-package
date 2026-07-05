-- ============================================================
-- V2 — admin_user.mfa_last_totp_step: TOTP replay 방지(RFC 6238 §5.2).
--
-- 재현감사 F02: TotpService.verifyAt 은 매 검증마다 현재±1 스텝(30초 윈도우
-- 3개)을 재계산해 문자열 비교만 하고, 어떤 카운터가 이미 소비됐는지 서버측
-- 상태가 전혀 없다. 동일 코드를 좁은 스킴 윈도우(최대 ~90초) 안에서 여러
-- 세션이 재사용해도 전부 통과한다(가로챈 코드 replay).
--
-- 컬럼은 NULLABLE(기본값 없음) — 기존 행(운영자)은 NULL 로 시작하고, 다음
-- verify/confirm/disable 성공 시 채워진다. NULL 이면 "아직 아무 스텝도
-- 소비하지 않음"으로 취급해 첫 검증을 막지 않는다(app 레벨: step > NULL 은
-- 항상 허용). 폭은 BIGINT 상당의 NUMBER(19,0) — floorDiv(epochMillis,30000)
-- 카운터가 long 범위를 쓰므로 손실 없이 저장.
-- ============================================================

ALTER TABLE admin_user ADD (mfa_last_totp_step NUMBER(19,0));
