# Flyway 마이그레이션 squash + 부트스트랩 초기화 설계

- **작성일**: 2026-06-27
- **대상**: `core/src/main/resources/db/migration/` (V1~V52), `scripts/` 부트스트랩, R__ 시드
- **전제**: **운영 배포 DB 없음** (사용자 확인) → 마이그레이션 히스토리 보존 의무 0
- **운영 타깃**: Oracle SE2 (별도 [SE2 호환성 감사](2026-06-27-se2-compatibility-audit.md) 참조)

---

## 1. 배경 / 목적

현재 마이그레이션은 V1~V52 (52개 파일, 2459줄)로 누적되어 있고, 다음과 같은 "역사적 흔적"을 포함한다:

- **VPD no-op 6개** — V3/V8/V20/V35/V42 (VPD 제거로 `SELECT 1 FROM dual` 화) + V52 (forward-only cleanup)
- **V19 RAW(16) UUID 대수술** — 377줄, 모든 테이블을 재생성하며 시퀀스를 제거
- **다수 ALTER 누적** — 컬럼 추가/제거, 제약 변경이 V6~V51에 흩어짐

운영 DB가 없으므로 이 누적을 **현재 최종 스키마를 그대로 담은 단일 V1 baseline**으로 재작성한다. 운영 타깃이 SE2이므로 처음부터 SE2-clean·환경중립으로 설계한다.

### 현재 최종 스키마 규모 (V52 적용 후)
- 테이블 21개 (플랫폼 6 + 테넌트 7 + 감사 5 + 기타), 시퀀스 6개, 뷰 1개 (V40 UUID 디버깅), VPD 객체 0개
- PL/SQL: signing_key bootstrap 패키지 등 (AUTHID DEFINER)

---

## 2. 산출물

1. `core/src/main/resources/db/migration/V1__baseline_schema.sql` — 신규 단일 baseline. 테이블·시퀀스·뷰·인덱스·제약·PL/SQL 패키지·GRANT·**인프라 시드** 전부 포함.
2. 기존 `V1`~`V52` 52개 파일 **삭제**.
3. `R__seed_operators.sql` 갱신 — 운영자 계정(alice + **bob**)을 여기로 일원화.
4. 정리된 부트스트랩 스크립트 — VPD 죽은 주석 제거 + `XEPDB1` → `${ORA_SERVICE}` 매개변수화.
5. 검증 통과 — 실DB A-vs-B 스키마 diff = 0 + 기존 IT 그린.

### 비산출물 (YAGNI)
- 실제 SE2 컨테이너 동적 검증 (SE2 이미지 확보 전제 → 별도 작업. squash 검증은 XE에서 충분).
- 스키마 리팩토링/개선 — squash는 **동등 변환**이 원칙, 스키마 의미를 바꾸지 않는다.
- 새 기능/테이블 추가.

---

## 3. baseline 생성 파이프라인 (실DB 기반)

손으로 합성하면 오타·누락 위험이 크므로, 실제 DB에서 최종 스키마를 추출한다.

```
1. 깨끗한 Oracle 컨테이너 기동 (gvenzl/oracle-xe:21-slim-faststart)
   + bootstrap-schema.sql 로 APP_OWNER 계정/역할/권한 설정
2. 현재 V1~V52 + R__seed_operators 를 Flyway 로 끝까지 적용 → "정답 스키마" 확보
3. DBMS_METADATA.GET_DDL 로 APP_OWNER 스키마 추출
   (테이블 · 시퀀스 · 뷰 · 인덱스 · 제약 · 트리거 · PL/SQL 패키지)
   - SET_TRANSFORM_PARAM 으로 STORAGE / TABLESPACE / SEGMENT_ATTRIBUTES = false
     → SE2-clean · 환경중립 (스토리지절/세그먼트속성 제거)
4. 추출물 정리: 의존성 순서 정렬(부모→자식 FK), GRANT 블록을 기존
   마이그레이션 기준으로 재구성, 가독성 주석 추가
5. → V1__baseline_schema.sql
```

---

## 4. 시드 데이터 처리

마이그레이션 내 INSERT 7곳(V14/V17/V18/V19/V26/V27/V31)을 두 종류로 구분한다.

### ① 인프라 시드 → V1 baseline 에 포함 (앱 동작 필수, 환경 무관)
- V14 `audit_chain_lock` 싱글톤 (AuditLogService append 직렬화 lock)
- V17 `mds_blob_cache` 싱글톤 ROW
- V18 `signing_key` bootstrap 함수/패키지
- V26/V27/V31 기본 정책/스냅샷 행 (스키마 초기 상태에 필요한 행)

### ② 운영자 계정 시드 → R__ repeatable 로 일원화
- 현재 V11 = alice(PLATFORM_OPERATOR), V29 = bob(RP_ADMIN) 가 versioned 마이그레이션에 박혀 있고, alice 는 `R__seed_operators.sql`(seed-common)에도 중복 존재.
- **결정**: V1 baseline 에서 운영자 계정 INSERT 를 제외한다. alice + bob 을 모두 `R__seed_operators.sql`(seed-common, 모든 비-prod 프로필 공통)로 단일화한다.
  - **주의**: bob 은 현재 V29 에만 있으므로, baseline 에서 빼면 사라진다 → 반드시 `R__seed_operators.sql` 로 옮긴다. (멱등 `WHERE NOT EXISTS` 가드 유지)
  - prod 는 seed 미포함 그대로 — 운영자 계정은 운영 절차로 별도 생성.

---

## 5. 검증 (핵심 게이트)

### A. squash 정확성 — A-vs-B 스키마 diff
새 V1 이 기존 52개와 동일한 최종 스키마를 만드는지 객관 증명:

```
1. [기존] 깨끗한 DB-A 에 V1~V52 적용 → 스키마 덤프 A
2. [신규] 깨끗한 DB-B 에 V1__baseline 만 적용 → 스키마 덤프 B
3. A vs B diff (테이블 · 컬럼 · 타입 · 제약 · 인덱스 · 시퀀스 · 뷰 · GRANT · 패키지)
   정규화: 이름순 정렬 + 스토리지절 제거 후 비교
   → 0 diff 여야 통과
```
손으로 정리한 baseline 의 누락/오타는 여기서 잡힌다.

### B. 기존 IT 그린
Testcontainers IT 전체 — baseline 하나만으로 앱 부팅·테넌트 격리(@Filter)·CRUD 가 동작하는지. 인프라 시드 누락은 IT 실패로 드러난다.

### C. testfix 무충돌 (확인 완료)
`db/testfix/V9000__test_disable_seed_mfa.sql` 는 `application-test.yml` 의 locations 로만 참조되고 V1 뒤(V9000)에 적용되므로 baseline 변경과 충돌 없음.

---

## 6. 위험 관리

| 위험 | 대응 |
|---|---|
| baseline 누락(컬럼/제약/GRANT/시퀀스) | §5.A A-vs-B diff 로 강제 검출 |
| 인프라 시드 누락(lock/싱글톤/bootstrap) → 부팅 실패 | §5.B IT 그린 게이트 |
| bob 시드 유실 | §4.② 에서 R__seed_operators 로 명시적 이전 |
| testfix V9000 충돌 | db/testfix 격리, V1 뒤 적용 — 무영향(확인됨) |
| 작업 중 main 오염 | per-phase **worktree** 작업 → 검증 후 `--no-ff` 머지 |
| `./gradlew build` 전체가 pre-existing 로 빨감 | base worktree 대조로 **회귀만** 판정 (SliceConfig/Oracle 경합은 기존 함정) |
| subagent 가 메인 repo 에 커밋 | 프롬프트에 cwd/브랜치 검증 가드 강제 |

---

## 7. 부트스트랩 스크립트 정리 (SE2 개선)

같이 손대는 김에 SE2 감사의 🟡 항목을 흡수한다:

1. **VPD 죽은 주석 제거** — `bootstrap-schema.sql`/`bootstrap-external-body.sql` 의 "VPD 제거됨" 설명 잔재 정리 (객체는 이미 미생성).
2. **`XEPDB1` → `${ORA_SERVICE}` 매개변수화** — `bootstrap-schema.sql:21`, `run-bootstrap.sh:7`, `reset-app-owner.sql:20` 의 하드코드 서비스명을 환경변수(`${ORA_SERVICE:-XEPDB1}`)로. 운영 SE2 PDB 명 대응. (외부 경로 `init-db-external.sh` 는 이미 매개변수화됨 — 일관성 맞춤.)

스크립트의 계정/권한 로직 자체는 변경하지 않는다 (SE2 호환 확인됨).

---

## 8. 작업 격리 (실행 절차)

메모리의 per-phase worktree 규칙:
1. main 에서 brainstorm(이 문서) → worktree 생성
2. worktree 안에서 baseline 추출·정리·스크립트 수정·검증 (상대경로 Write, `git status` 검증)
3. §5 검증 전부 통과 후 main 으로 `--no-ff` 머지

---

## 9. 영향 받지 않는 것 (확인됨)

- **Flyway 설정** — `baseline-on-migrate: true`/`baseline-version: 0`(비-prod), prod 의 `validate-on-migrate: true` 모두 그대로. 신규 DB 만 대상이므로 체크섬 충돌 없음.
- **R__ 환경별 시드** — `R__seed_dev_tenant.sql`(dev), `R__seed_local_tenant.sql`(local) 는 그대로. baseline 적용 후 자동 실행.
- **Testcontainers** — 매번 새 DB, `flyway_schema_history` 누적 없음.
