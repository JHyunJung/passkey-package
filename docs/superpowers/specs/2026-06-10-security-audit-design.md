# 보안 심층 감사 설계 (fresh, 제로베이스)

- **작성일**: 2026-06-10
- **상태**: 승인됨 (브레인스토밍 완료)
- **요청**: 보안 전문가 관점의 전체 코드베이스 심층 분석. **이전 감사 기록(2026-05-31 10-lens 감사, 백로그 59건)은 입력으로 사용하지 않는다** — 완전히 독립적인 재감사.

## 1. 목표와 원칙

전체 코드베이스를 제로베이스에서 보안 관점으로 재감사한다. 모든 finding은 다음 셋을 충족한 것만 보고서에 올린다:

1. 코드 위치가 특정됨 (현재 main 기준 실존 file:line)
2. 독립 에이전트의 적대적 검증(반박 시도)을 통과함
3. SaaS·온프레미스 양쪽 위협 모델에서 악용 가능성이 판정됨

산출물은 검증된 보고서와 우선순위화된 개선 설계까지. 구현은 별도 사이클.

## 2. 위협 모델 (둘 다 평가)

| 모델 | 전제 | 주요 관점 |
|---|---|---|
| SaaS 멀티테넌시 | 리버스 프록시/LB 뒤 배포, VPD on, 여러 테넌트 공존 | 외부 공격자, 악성 테넌트, cross-tenant 누출 |
| 온프레미스 | 고객사 내부망, VPD off(외부 SE DB), 단일 테넌트 성격 | 내부망 침투자, DB-only 유출, 설정 위생 |

finding마다 두 모델 각각의 악용 가능성·영향을 별도 판정한다 (한쪽에서만 유효한 finding 허용).

## 3. 감사 아키텍처 (3단계 워크플로)

### Phase 1 — 발견: 신뢰경계 7렌즈 fan-out

공격자가 실제로 드나드는 경계별로 렌즈를 나누고, 각 렌즈 안에서 STRIDE 6항목(위장 Spoofing / 변조 Tampering / 부인 Repudiation / 정보노출 Information disclosure / DoS / 권한상승 Elevation of privilege)으로 빠짐을 체크한다.

| # | 렌즈 | 대상 |
|---|---|---|
| 1 | 외부 RP API 경계 | passkey-app: API key 인증, rate-limit, WebAuthn ceremony 입력 처리 |
| 2 | 관리자 인증 경계 | admin-app: 로그인/MFA/복구코드/세션/CSRF/초대/비밀번호 재설정 |
| 3 | 테넌트 격리 경계 | VPD on/off 둘 다, TenantBoundary, VPD-exempt 경로 |
| 4 | 암호 코어 | webauthn 자체 verifier, JWT/id-token 발급·검증, 키관리/회전, 해시/서명 |
| 5 | 클라이언트·SDK 경계 | admin-ui, sdk-java, rp-app 무상태 릴레이 |
| 6 | 공급망·설정·비밀관리 | 의존성, docker/deploy/scripts, 환경변수, 로깅/마스킹 |
| 7 | 데이터 보호 | at-rest 암호화, audit chain, 보존/삭제, PII |

각 finding의 구조:

```
{ id, 경계, STRIDE 분류, 위치(file:line), 설명, 악용 시나리오,
  SaaS 영향, 온프레미스 영향, 제안 방향 }
```

### Phase 2 — 적대적 검증

finding마다 독립 검증 에이전트가 "이것은 오탐이다"를 입증하려 시도한다 — 실제 코드를 다시 읽고 방어가 이미 존재하는지, 악용 전제가 성립하는지 반박. 반박에 실패한 것만 confirmed. 심각도(critical/high/medium/low/info)는 검증 에이전트가 악용 난이도 × 영향으로 재판정한다.

### Phase 3 — 종합

confirmed finding을 중복 제거·연관 묶음화하고, 위협모델별 심각도 매트릭스와 우선순위(즉시/단기/중기)를 부여해 보고서를 생성한다.

## 4. 산출물

1. **보고서**: `docs/superpowers/audit/2026-06-10-security-audit-report.md` + 원본 JSON (`2026-06-10-security-audit-findings.json`). 이전 감사와의 비교는 하지 않는다(완전 독립).
2. **개선 설계 spec**: 보고서 검토 후 개선 대상을 사용자와 함께 선별하여 `docs/superpowers/specs/2026-06-10-security-hardening-design.md` 작성 → writing-plans로 연결.

## 5. 범위 명세

- **포함**: `core`, `passkey-app`, `admin-app`, `admin-ui`, `rp-app`, `sdk-java`, `webauthn`, `deploy`/`docker-compose.yml`/`scripts`, Flyway 마이그레이션(`core/src/main/resources/db/migration`), gradle 의존성 선언.
- **제외**: `docs` 디렉토리, 테스트 코드 자체의 품질. 단, 보안 테스트 공백(있어야 할 회귀 테스트의 부재)은 finding으로 허용.

## 6. 검증 게이트

- 발견 단계의 자기 보고는 신뢰하지 않는다 — confirmed 판정은 전적으로 Phase 2 검증 에이전트의 코드 재확인에 의존.
- 보고서의 모든 위치 인용은 현재 main 기준 실존 라인이어야 한다.
- 검증 에이전트는 발견 에이전트와 다른 컨텍스트에서 출발한다(발견 근거를 그대로 물려받지 않고 코드에서 재확인).
