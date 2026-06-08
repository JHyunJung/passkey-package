-- 자체 WebAuthn verifier로 전환하며 credential 저장 표현을 변경한다.
-- 기존 데이터 없음(초기 구축) → 단순 컬럼 정의.
--
-- 변경 전: public_key BLOB = webauthn4j JSON 엔벨로프(ao/cd/ce/tr).
-- 변경 후: cose_public_key BLOB = COSE_Key CBOR (서명검증 키 그 자체).
--          기존 public_key 컬럼은 제거한다 — 더 이상 통짜 엔벨로프를 저장하지 않는다.
--
-- 컬럼 변경 DDL은 VPD(tenant_id 기반, V3/V20) 영향을 받지 않는다(VPD는 DML 술어만 재작성).
-- public_key 를 참조하는 뷰/객체 없음(V40 readable view 는 BLOB 제외) → DROP COLUMN 안전.

-- 안전망(codex P1): 이 마이그레이션은 "기존 credential 데이터 없음" 전제 하에
-- public_key→cose_public_key 무손실 전환이 아니라 단순 재정의를 한다. 만약 행이
-- 존재하면 EMPTY_BLOB() 백필이 유효 키를 무효화하고 public_key를 잃으므로,
-- 행이 있으면 마이그레이션을 즉시 실패시켜 silent 데이터 손상을 막는다.
-- (COUNT/IF/RAISE PL/SQL 가드 + `/` 종료 패턴은 V18 signing_key 부트스트랩과 동일.)
--
-- [한계 — best-effort] CREDENTIAL 에는 VPD 정책(CREDENTIAL_TENANT_ISOLATION, V3)이
-- 붙어 있고 술어는 tenant_id = SYS_CONTEXT('APP_CTX','TENANT_ID') 이다. Flyway
-- 마이그레이션 실행 시점엔 APP_CTX 가 설정돼 있지 않으므로, VPD 가 활성(EE)이고
-- 소유자가 정책 대상인 경로에서는 이 SELECT COUNT(*) 가 술어 UNKNOWN 으로 인해
-- 0행을 볼 수 있다(V3 주석: "APP_CTX unset → 모든 행 필터"). 즉:
--   - SE2(VPD 미부착, V3 의 ORA-00439 swallow) / 소유자 VPD 면제 경로: 가드 유효.
--   - EE + VPD 활성 + 컨텍스트 부재: COUNT 가 0 으로 보여 가드가 우회될 수 있음.
-- 그래도 가드는 해롭지 않고(0행이면 어차피 no-op) 일반 환경에서 손상을 잡으므로 둔다.
-- 데이터가 있는 환경으로의 전환이 필요하면 별도 백필 마이그레이션이 선행돼야 한다.
DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM credential;
  IF v_count > 0 THEN
    RAISE_APPLICATION_ERROR(-20044,
      'V44 aborted: credential table has ' || v_count ||
      ' row(s). This migration assumes an empty table (initial build) and would ' ||
      'destroy existing key material. Backfill cose_public_key from public_key first.');
  END IF;
END;
/

ALTER TABLE credential ADD (cose_public_key BLOB);

-- 행이 없음을 위에서 보장했으므로 백필은 사실상 no-op이지만, ADD→NOT NULL 사이
-- 안전을 위해 둔다.
UPDATE credential SET cose_public_key = EMPTY_BLOB() WHERE cose_public_key IS NULL;
-- NOT NULL 제약만 추가한다. 기존 LOB 컬럼에 MODIFY 하면서 데이터 유형(BLOB)을
-- 다시 명시하면 Oracle 이 LONG→LOB 변환 옵션으로 해석해 ORA-22296 을 던진다
-- (Oracle: "기존 LOB 컬럼의 MODIFY 에는 datatype 을 재지정하지 않는다"). 따라서
-- 제약 절만 둔다 — 컬럼은 이미 BLOB 이므로 유형 재선언은 불필요하다.
ALTER TABLE credential MODIFY (cose_public_key NOT NULL);

ALTER TABLE credential DROP COLUMN public_key;
