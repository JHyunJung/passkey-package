# scripts/ 폴더 정리 설계

- 날짜: 2026-06-29
- 대상: `scripts/` (13개 파일 → 10개)
- 산출물: 고립 도구 3개 삭제, DB 인프라 10개 유지

## 1. 배경과 목표

`scripts/`에 13개 파일이 쌓여 있다. 일부는 빌드·테스트·로컬/외부 DB 초기화의 핵심 인프라이고,
일부는 특정 일회성 작업에 묶인 도구다. **반드시 필요한 것만 남기고 정리**한다.

판단 기준: **"코드/빌드/테스트의 실행 경로에서 참조되는가"**. Gradle 태스크·Testcontainers
IT·docker-compose·다른 스크립트가 호출하면 필수다. 반면 문서(`.md`)에서 언급만 되는 것은
유지 사유가 아니다 — 그 문서가 이미 끝난 작업의 역사적 기록일 수 있기 때문이다.

조사 방법: 각 파일에 대해 `grep -rn`으로 전체 repo 참조를 집계하고, 참조처를 **실행 경로 /
살아있는 문서 / 역사 기록 / 자기 클러스터 내부**로 분류했다.

## 2. 의존성 그래프 (조사 결과)

13개 파일은 2개의 DB 클러스터 + 2개의 고립 도구로 나뉜다.

### 클러스터 A — Docker 기반 로컬 dev (전부 유지)

```
init-dev-db.sh ──► wait-for-oracle.sh
              └──► run-bootstrap.sh ──► bootstrap-schema.sql ★
reset-app-owner.sh ──► reset-app-owner.sql
                  └──► run-bootstrap.sh ──► bootstrap-schema.sql ★
```

- `bootstrap-schema.sql` ★ — **single source of truth**. `core/build.gradle.kts`와
  `admin-app/build.gradle.kts`의 `processTestResources` 태스크가 테스트 클래스패스로 복사하고,
  수십 개 Testcontainers IT(AppLevelIsolationIT, TenantFilterAspectIT, AdminFlowIT 등)가
  MountableFile로 컨테이너에 주입한다. docker-compose 로컬 dev와 CI가 같은 파일을 써서 drift를
  막는다. **절대 삭제 불가.**
- `run-bootstrap.sh` — `bootstrap-schema.sql`을 실행하는 러너(작업 디렉토리 무관 sibling 해석).
- `wait-for-oracle.sh` — docker healthcheck가 healthy 될 때까지 대기.
- `init-dev-db.sh` — `docker compose down -v` 후 위 셋을 묶어 풀 초기화(분 단위).
- `reset-app-owner.sh` + `reset-app-owner.sql` — 컨테이너 유지한 채 APP_OWNER 스키마만 빠른
  리셋(초 단위). dev/local 프로필 가드.

### 클러스터 B — 외부/원격 Oracle dev, Docker 없음 (전부 유지)

```
init-db-external.sh ──► bootstrap-external-body.sql   (sqlplus DEFINE 주입)
                   └──► Flyway 적용
bootstrap-external.sql (DBeaver wrapper) ──► bootstrap-external-body.sql
reset-app-owner-external.sql (DBeaver, APP_OWNER 계정 self-service)
```

- `init-db-external.sh` — 도커 없이 이미 떠 있는 Oracle(로컬 설치/원격 dev 서버)에 부트스트랩+
  Flyway 적용. sqlplus로 `bootstrap-external-body.sql` 실행.
- `bootstrap-external.sql` — DBeaver 등 DEFINE 미지원 클라이언트용 wrapper.
- `bootstrap-external-body.sql` — 실제 부트스트랩 body. sqlplus/init-db-external.sh가 직접 호출.
- `reset-app-owner-external.sql` — 외부 SE의 APP_OWNER 스키마를 DBeaver에서 APP_OWNER 계정으로
  리셋(SYSDBA·sqlplus 불필요). [[project_external_se_db_reset]] 메모리의 그 스크립트.

이 클러스터는 운영 dev 서버(SE2, Docker 부재) 배포·초기화에 쓰이므로 전부 유지한다.

### 고립 도구 2종 (삭제)

- `build-docs-pdf.sh` + `docs-pdf.css` — `docs/rp-server-api.md` → PDF 변환(wkhtmltopdf 0.12.6).
  **서로만 참조하는 고립 쌍.** 살아있는 코드/문서에서 호출하는 곳 0건.
- `verify-lombok-bytecode.sh` — Lombok 리팩터가 바이트코드를 안 바꿨음을 capture-before/
  verify-after로 증명하는 도구. **코드 참조 0건.** 2026-06-14 Lombok 도입 plan/spec(완료·머지)
  에서만 언급된다.

## 3. 삭제 결정 (3개)

| 파일 | 사유 | 복구 |
|---|---|---|
| `verify-lombok-bytecode.sh` | Lombok 도입(2026-06-14 완료) 검증 전용. 코드 참조 0. 향후 새 Lombok 작업은 드물고, 필요 시 복구 가능 | git 이력 |
| `build-docs-pdf.sh` | PDF 생성 도구. docs-pdf.css와 고립 쌍. 사용자 결정: PDF는 수동/미사용 | git 이력 |
| `docs-pdf.css` | 위 스크립트 전용 스타일. 단독 의미 없음 | git 이력 |

**보존되는 것:**
- `docs/rp-server-api.pdf` (생성물) — 외부 RP사 산출물이므로 유지. 사용자 결정.
- `docs/rp-server-api.md` (원본) — 유지.
- 2026-06-14 Lombok plan/spec 문서 — **수정하지 않는다.** 그 시점엔 스크립트가 실재했고,
  완료된 작업의 역사적 기록이다. 역사 문서를 소급 수정하면 그 작업의 맥락이 왜곡된다.

## 4. 유지 결정 (10개)

클러스터 A(6) + 클러스터 B(4) 전부. 각각 빌드/테스트/로컬 dev/외부 dev 초기화의 실행 경로에서
참조되거나(`bootstrap-schema.sql`은 Gradle·IT 직접 사용), 그 실행 체인의 일부다. YAGNI 기준을
적용해도 전부 "현재 쓰이는" 인프라라 삭제 대상이 아니다.

## 5. 정리 방법

1. `git rm scripts/verify-lombok-bytecode.sh scripts/build-docs-pdf.sh scripts/docs-pdf.css`
   (이력 보존 → 복구 가능).
2. README.md는 **손대지 않는다.** README 145줄의 PDF 언급은 admin-ui `/audit-chain` 월간
   보고서(`openhtmltopdf`, 런타임 기능)에 대한 것으로, 삭제하는 빌드 도구(`build-docs-pdf.sh`,
   wkhtmltopdf)와 무관하다(조사 확인).
3. Lombok plan/spec(2026-06-14) 문서는 역사 기록이므로 수정하지 않는다(§3).

## 6. 검증

1. 삭제 후 실행 경로 참조 0 재확인:
   `grep -rn "verify-lombok-bytecode\|build-docs-pdf\|docs-pdf.css" . --exclude-dir=.git`
   → 남는 건 2026-06-14 Lombok 역사 문서뿐이어야 한다(살아있는 코드/빌드/스크립트 0건).
2. bootstrap-schema 복사 태스크 무사 확인(삭제와 무관하지만 안전 점검):
   `./gradlew :core:processTestResources :admin-app:processTestResources` → BUILD SUCCESSFUL.
3. `scripts/` 최종 10개 확인: 클러스터 A 6 + 클러스터 B 4.

## 7. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| 삭제 도구가 실은 어딘가에서 호출됨 | 전수 grep으로 실행 경로 참조 0 확인 완료(역사 문서만 잔존) |
| 향후 PDF/Lombok 검증 재필요 | git 이력에서 복구 가능. 단일 커밋이라 revert도 쉬움 |
| 역사 문서의 깨진 링크 | 의도적 보존 — 완료된 작업의 기록이므로 수정 안 함 |
| 생성 PDF 손실 우려 | rp-server-api.pdf 유지(삭제 대상 아님) |
