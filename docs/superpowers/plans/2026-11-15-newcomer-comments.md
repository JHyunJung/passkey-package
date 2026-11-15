# 신규 개발자용 주석 정비 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 는 알지만 Kotlin 과 이 프로젝트가 처음인 개발자를 위해, 본 코드의 주석을 정비한다(필수 주석 추가 + 작업 흔적 표기 정리). 동작 변경 0.

**Architecture:** 모듈·패키지 묶음 단위로 Task 를 나눠 주석만 고친다. 각 Task 는 "주석을 걷어낸 소스가 base 와 동일"함을 도구로 증명하고 컴파일을 통과한 뒤 커밋한다. 도구는 저장소에 커밋하지 않는다(`.claude/` 는 gitignore).

**Tech Stack:** Java 17, Kotlin 2.4.21, Gradle 9.7 멀티모듈, Python 3.9(검증 도구).

**Spec:** `docs/superpowers/specs/2026-11-15-newcomer-comments-design.md` — 구현자는 3절(주석 규칙)을 반드시 처음부터 끝까지 읽는다. 이 계획은 그 내용을 반복하지 않는다.

## Global Constraints

- 작업 위치: worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/newcomer-comments`, 브랜치 `docs/newcomer-comments`. 시작할 때 `pwd` 와 `git branch --show-current` 로 확인한다. 메인 저장소 경로(`/Users/jhyun/Git/10-work/crosscert/Passkey2/<module>/…`)에 쓰지 않는다 — 파일 경로는 worktree 기준 상대 경로를 쓴다.
- BASE = `99a3c3e4808c15f0e17c9b9894736835a8416df0` (설계 문서 커밋. 이 뒤로는 주석 변경만 쌓인다).
- 바꿀 수 있는 것: 각 Task 범위의 `src/main` 아래 `*.java`·`*.kt` 파일의 **주석뿐**. 주석이 아닌 줄, 공백 줄 배치, import, 파일 추가·삭제·이름 변경은 금지.
- 테스트 코드, HTML, SQL, yml, 스크립트, `*.gradle.kts`, 문서(`docs/**`, 이 계획 파일의 체크박스 제외)는 건드리지 않는다.
- 새 주석은 한국어. 영어 주석은 번역하지 않는다. 영어 주석 안의 흔적 표기는 영어 문장을 유지한 채 표기만 걷어낸다.
- 문체 금지 목록(spec 3.4)은 새로 쓰거나 고친 주석에 적용한다.
- 주석이 하나도 추가되지 않는 파일이 많은 것이 정상이다. 애매하면 쓰지 않는다.
- 기능이 있는 주석은 그대로 둔다: `//noinspection`, `// language=…`, `@formatter:off/on`, `//region`, `NOSONAR`, `@Suppress` 근거 주석, 라이선스 헤더.
- sdk-java 버전·CHANGELOG·QA 번들은 손대지 않는다. 재배포 없음.
- 커밋 메시지: `docs(<module>): …`, 마지막 줄은 정확히 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>` 한 줄. 다른 트레일러를 붙이지 않는다. push 하지 않는다.
- 전체 `./gradlew build`·전체 테스트는 돌리지 않는다(이 저장소에서 원래 빨갛고 admin-app 은 2시간 가까이 걸린다). 10분 넘게 걸릴 명령은 백그라운드로 띄운다.

## Review Focus

테스트가 잡지 못하고 사람이 당하기 쉬운 것들. 리뷰어는 이 순서로 본다.

1. **틀린 주석** — "null 이면 바깥 catch 가 빈 목록으로 처리한다" 같은 문장이 실제 호출 경로와 맞는가. 고친 문장마다 코드에서 근거 줄을 찾는다.
2. **사라진 근거** — 삭제·축약된 주석에 보안(타이밍 균등화, 열거 방어, fail-closed), 동시성(락, lost-update), 계약(RP API, 세션 직렬화, DB 스키마) 근거가 있었는가.
3. **도메인 용어를 흔적으로 오인** — `P-256`/`P256`, `S256`, `ES256`, FIDO 스펙 절 번호, `F5`(키), HTTP 상태 등은 흔적이 아니다.
4. **깨진 참조** — 고친 KDoc 의 `[Symbol]`, Javadoc 의 `{@link …}`·`@see` 가 실제 심볼을 가리키는가. 주석 안에 `*/` 가 들어가 주석이 일찍 닫히지 않는가.
5. **기계적인 문체** — 파일마다 같은 틀, 고른 길이, 합니다체, 코드를 되읽는 문장, 모든 메서드에 붙은 주석.

---

## 공통 절차 (Task 2~11 이 모두 따른다)

각 Task 의 **Scope** 는 디렉터리 목록이다. 아래에서 `<SCOPE>` 는 그 목록, `<MODULE>` 은 해당 Gradle 모듈이다.

- [ ] **P1. 후보 수집**

```bash
# 작업 흔적 후보 (후보일 뿐 — 하나씩 읽고 판단한다)
grep -rnE '(//|\*).*(\bPhase [0-9A-Z]|\bTask [0-9]|\bT[0-9]{1,2}\b|\b[SPFG][0-9]{1,2}(-[0-9])?\b|QW-[0-9]|spec §|followup|[Cc]odex|원 Java|기준 Java|Java 원본|원본 Java|Kotlin 전환|전환 전|\(V[0-9]{1,2}\)|\bV[0-9]{1,2} ?(의|에서|로|부터|이후))' \
  --include='*.kt' --include='*.java' <SCOPE>
# 주석이 하나도 없는 파일
find <SCOPE> \( -name '*.kt' -o -name '*.java' \) -exec grep -LE '^\s*(//|/\*|\*)' {} +
# (Kotlin 모듈만) Java 개발자가 걸리는 지점
grep -rnE '!!|@(field|get|param|set):|companion object|@Jvm[A-Za-z]+|\binternal\b|serialVersionUID|data class .*ByteArray|\.trim \{|\.split\(' --include='*.kt' <SCOPE>
```

- [ ] **P2. 파일을 읽고 주석을 고친다** — 범위의 모든 파일을 처음부터 끝까지 읽는다(후보에 안 걸린 파일도). spec 3절대로:
  흔적 표기 정리(3.2) → 진입점·흐름·이유(3.1) → Kotlin 지점(3.3). "원 Java 와 같은 자리" 류를 고쳐 쓸 때는
  그 예외를 받는 쪽(catch, `@ExceptionHandler`, 호출자)을 코드에서 실제로 찾은 뒤에만 쓴다. 못 찾으면 표기만 걷어낸다.
  Flyway 번호는 `V7` 이상만 지운다(현재 마이그레이션은 `core/src/main/resources/db/migration` 의 V1~V6).

- [ ] **P3. 주석만 바뀌었는지 증명**

```bash
python3 .claude/tools/comment_check.py verify 99a3c3e4808c15f0e17c9b9894736835a8416df0
```
Expected: 마지막 줄 `OK: code identical` (exit 0). `DIFF`/`OUT-OF-SCOPE` 줄이 하나라도 있으면 그 파일을 고쳐서 다시 돌린다.

- [ ] **P4. 컴파일**

```bash
./gradlew :<MODULE>:compileJava :<MODULE>:compileKotlin :<MODULE>:compileTestJava :<MODULE>:compileTestKotlin --console=plain -q
```
(Java 전용 모듈은 `compileKotlin`·`compileTestKotlin` 을 뺀다.) Expected: 출력 없이 exit 0.

- [ ] **P5. 자기 점검** — `git diff` 를 처음부터 끝까지 읽는다.
  (a) 추가·수정한 문장마다 근거가 되는 코드 줄을 다시 확인한다.
  (b) `-` 줄(삭제된 주석)에 보안·동시성·계약 근거가 있으면 `+` 쪽에 살아 있는지 확인한다.
  (c) Review Focus 3·4·5.
  (d) P1 의 흔적 후보 grep 을 다시 돌려, 남은 것이 전부 도메인 용어이거나 의도적으로 남긴 것인지 확인한다.

- [ ] **P6. 커밋** — 범위 파일만 `git add` 한다(`git add -A` 금지). 메시지 `docs(<module>): <범위> 주석 정비 — 신규 개발자용 설명 보강·작업 흔적 정리`.

**보고 형식(구현자 → 컨트롤러):** 바뀐 파일 수 / 주석 추가·고쳐 씀·삭제 건수(대략) / 의도적으로 남긴 흔적 후보와 이유 / 읽다가 발견해 고친 틀린 기존 주석 / 확신이 없어 손대지 않은 곳 / P3·P4 출력 마지막 줄 / 커밋 SHA.

---

### Task 1: 검증 도구

**Files:**
- Create: `.claude/tools/comment_check.py` (gitignore 대상 — 커밋하지 않는다)

**Interfaces:**
- Produces:
  - `python3 .claude/tools/comment_check.py selftest` → 마지막 줄 `SELFTEST OK`, exit 0.
  - `python3 .claude/tools/comment_check.py verify <base-sha>` → base 대비 작업 트리(커밋된 것 + 미커밋 변경 모두)를 검사. 문제 없으면 마지막 줄 `OK: code identical`, exit 0. 문제는 `DIFF <path>`(주석 제거 후 내용이 다름, 첫 차이 줄 표시), `OUT-OF-SCOPE <path>`(허용 범위 밖 파일이 바뀜·추가·삭제됨) 로 출력하고 exit 1.
  - 허용 범위: `{core,webauthn,passkey-app,admin-app,sdk-java,rp-app}/src/main/**/*.{java,kt}` 의 내용 수정. 그 밖에 허용되는 변경은 `docs/superpowers/plans/2026-11-15-newcomer-comments.md` 하나뿐.

- [ ] **Step 1: `strip(text: str, lang: str) -> str` 구현** (`lang` 은 `"java"` 또는 `"kt"`)

  한 글자씩 읽는 상태 기계로 만든다(정규식 치환 금지).
  - 문자열·문자 리터럴 안은 그대로 보존: Java `"…"`, `'…'`, 텍스트 블록 `"""…"""`; Kotlin `"…"`, `'…'`, raw string `"""…"""`. 역슬래시 이스케이프 처리.
  - Kotlin 문자열 템플릿 `${ … }` 는 중괄호 깊이를 세어 통째로 문자열의 일부로 보존한다(안에 중첩 문자열이 있어도).
  - `//` 주석은 줄 끝까지 제거. `/* … */` 는 제거하되 Kotlin 은 중첩을 센다. 줄바꿈을 포함한 블록 주석은 줄바꿈 하나로, 한 줄 안의 블록 주석은 공백 하나로 바꾼다.
  - 정규화: 문자열 밖 연속 공백은 하나로, 각 줄 앞뒤 공백 제거, 빈 줄 제거. **줄바꿈은 유지한다**(Kotlin 은 줄바꿈이 문장 구분이다).

- [ ] **Step 2: `selftest` 구현 — 아래 경우를 단언한다**

  같아야 하는 쌍(`strip(a) == strip(b)`):
  - 줄 주석 추가/삭제, 코드 뒤 꼬리 주석 추가/삭제, KDoc·Javadoc 블록 추가/삭제, 주석 문구만 변경, 주석 줄 들여쓰기 변경.
  - Kotlin 중첩 주석 `/* a /* b */ c */` 삭제.

  달라야 하는 쌍(`strip(a) != strip(b)`):
  - 문자열 안 내용 변경: `"http://a"` → `"http://b"`, `"/* x */"` → `"/* y */"`, raw string `"""// a"""` → `"""// b"""`.
  - 템플릿 안 변경: `"${a /* x */}"` → `"${b}"`.
  - char 리터럴 `'"'` 뒤의 코드 변경, `'\''` 뒤의 코드 변경.
  - 줄바꿈 변경: `return\nx` → `return x`.
  - 식별자 한 글자 변경, 줄 삭제, 애너테이션 삭제.

  보존 확인: `strip('val u = "http://x" // c', "kt")` 결과에 `"http://x"` 가 그대로 있고 `// c` 가 없다.

- [ ] **Step 3: `verify` 구현** — `git diff --name-status <base>`(작업 트리 포함)와 `git ls-files --others --exclude-standard` 로 바뀐·새 파일을 모은다. 허용 범위 밖이거나 상태가 `M` 이 아니면 `OUT-OF-SCOPE`. 범위 안 `M` 파일은 `git show <base>:<path>` 와 작업 트리 파일을 `strip` 해서 비교, 다르면 `DIFF` 와 첫 차이 줄 출력. 끝에 검사한 파일 수와 판정.

- [ ] **Step 4: 실행해서 확인**

```bash
python3 .claude/tools/comment_check.py selftest      # SELFTEST OK
python3 .claude/tools/comment_check.py verify 99a3c3e4808c15f0e17c9b9894736835a8416df0   # OK: code identical (계획 파일만 바뀐 상태)
```
  그리고 실제로 잡는지 한 번 확인한다: 임의의 `.kt` 파일에서 코드 한 글자를 바꾸고 `verify` 가 `DIFF` 로 exit 1 인지 본 뒤 `git checkout -- <file>` 로 되돌린다. 전 소스에 대해 `strip` 이 예외 없이 도는지도 확인한다(모든 `src/main` 파일을 한 번씩 통과).

커밋 없음.

---

### Task 2: passkey-app (기준점)

**Scope:** `passkey-app/src/main/kotlin` (Kotlin 38파일, 약 2,100줄). MODULE=`passkey-app`.

이 Task 가 이후 Task 의 말투 기준이 된다. 공통 절차 P1~P6.

이 모듈에서 반드시 살필 것:
- 요청 흐름: 필터 순서(`RateLimitFilter` → `ApiKeyAuthFilter` → …)와 `/api/v1/rp/**` 컨트롤러 → 등록/인증 start·finish 서비스 → `ChallengeStore`. 처음 보는 사람이 `PasskeyApplication.kt` 에서 출발해 따라갈 수 있는지.
- `app/api/v1/rp/dto` 의 nullable 필드 + `@field:` 제약(spec 3.3). DTO 마다 반복하지 말고 대표 한 곳에 이유를 쓰고 나머지는 필요할 때만 한 줄.
- `ChallengeStore` 의 `Optional` 유지 이유, `data class` 의 `ByteArray`.
- `P1:`, `P1-2`, `codex P2:` 표기(약 45곳).

- [ ] P1 ~ P6 수행

### Task 3: admin-app — 기동·인증·운영자

**Scope:** `admin-app/src/main/kotlin/com/crosscert/passkey/admin/` 의 최상위 파일(`AdminApplication.kt`)과 `config/`, `auth/`, `operator/`, `web/auth/`, 그리고 `web/` 최상위 파일 1개. MODULE=`admin-app`.

살필 것: 보안 필터 체인 구성과 순서(`AdminSecurityConfig`), 로그인 → MFA pending → MFA 등록 강제 세션의 상태 전이, 세션(Redis) 직렬화 클래스의 `serialVersionUID`, 가입 요청·승인, 비밀번호 재설정과 커밋 후 메일 발송. `F02` 류 표기.

- [ ] P1 ~ P6 수행

### Task 4: admin-app — 감사·관측·운영 잡

**Scope:** 같은 루트의 `audit/`, `activity/`, `insights/`, `funnel/`, `retention/`, `scheduler/`, `system/`, `license/`, `keymgmt/`, `mds/`.

살필 것: 감사 로그 해시 체인(append 시 락, 검증), 스케줄 잡과 lease(왜 해제하지 않는 잡이 있는지 — 코드와 기존 주석에서 확인되는 범위만), 키 회전, MDS 동기화, KST 일 단위 집계.

- [ ] P1 ~ P6 수행

### Task 5: admin-app — 테넌트·API 키·크리덴셜·정책

**Scope:** 같은 루트의 `tenant/`, `apikey/`, `credential/`, `policy/`.

살필 것: 주석 없는 컨트롤러·DTO(`TenantAdminController`, `TenantAdminDto`, `ApiKeyAdminController`, `CredentialAdminController`) — 이름으로 충분하면 쓰지 않는다. 테넌트 경계 검사(RP_ADMIN 이 볼 수 있는 테넌트), `?: throw IllegalArgumentException` 3곳, `P0-2 seam` 표기.

- [ ] P1 ~ P6 수행

### Task 6: admin-app — 화면 공통

**Scope:** 같은 루트의 `web/shell/`, `web/support/`, `web/format/`, `web/license/`, `web/insights/`, `web/audit/`, `web/activity/`.

살필 것: Thymeleaf + htmx 화면 컨트롤러의 공통 규약(조각 응답, 로드 실패 처리), 템플릿·SpEL 이 이름으로 찾는 멤버(`internal` 금지, 프로퍼티/메서드 구분), `StandaloneJson`. `CsvWriter.kt` 는 `CsvWriterTest` 가 소스를 읽는다 — 이 파일에 BOM 문자(U+FEFF)를 실제 문자로 넣지 않는다.

- [ ] P1 ~ P6 수행
- [ ] `./gradlew :admin-app:test --tests '*CsvWriterTest' --console=plain -q` → exit 0

### Task 7: admin-app — 테넌트 상세·설정 화면

**Scope:** 같은 루트의 `web/tenant/`, `web/settings/` (18파일, 약 6,100줄).

"`!!`: 원 Java … 와 같은 자리" 주석이 가장 많은 곳이다. 한 건씩: 그 `!!` 가 try 안인지 밖인지, NPE 를 누가 받는지(같은 메서드의 catch / `LoadError` 처리 / 전역 500)를 코드에서 확인하고 현재 동작으로 다시 쓴다. 같은 파일에 같은 패턴이 여러 번이면 첫 곳에 이유를 쓰고 나머지는 짧게. `auth: Authentication?` 를 nullable 로 받는 이유(403 판정 순서).

- [ ] P1 ~ P6 수행

### Task 8: core — 엔티티·리포지토리·테넌트 격리

**Scope:** `core/src/main/java/com/crosscert/passkey/core/` 의 `entity/`, `repository/`, `tenant/`, `config/`, `util/`. MODULE=`core` (Java 전용).

가볍게 간다(spec 4절). 살필 것: 테넌트 격리가 Hibernate `@Filter` 로 이뤄지는 방식과 켜지는 지점, 필터를 타지 않는 native 쿼리, `Credential`·`AdminUser` 가 전체 컬럼 UPDATE 라 락 없이 수정하면 안 되는 이유, `Phase F2`·`QW-3`·`P0-4`·`(V18)` 류 표기. 엔티티 필드마다 주석을 달지 않는다.

- [ ] P1 ~ P6 수행

### Task 9: core — 라이선스·JWT·세레모니·공통

**Scope:** 같은 루트의 `license/`, `jwt/`, `ceremony/`, `api/`, `alert/`, `logging/`, `mail/`, `mds/`, `policy/`.

살필 것: 서명 키 봉인(master key → 파생 키), ID Token 발급, 라이선스 상태 기계, 응답 봉투(`ApiResponse`)와 예외 → 에러 코드 매핑, 로그 마스킹. `Phase 3 T5`·`Phase 3 rewrite`·`P1-3` 류 표기.

- [ ] P1 ~ P6 수행

### Task 10: webauthn

**Scope:** `webauthn/src/main/java` (Java 60파일). MODULE=`webauthn` (Java 전용).

가볍게 간다. 살필 것: 등록·인증 검증이 거치는 단계의 순서(진입점 verifier 에서 한 번), attestation 포맷별 분기, 수동 DER/CBOR 파서의 길이 검사가 뺄셈 형태인 이유(이미 주석이 있으면 그대로). W3C/FIDO 스펙 절 번호는 흔적이 아니다 — 남긴다.

- [ ] P1 ~ P6 수행

### Task 11: sdk-java · rp-app

**Scope:** `sdk-java/src/main/java`, `rp-app/src/main/java`. MODULE 은 각각 `sdk-java`, `rp-app` (Java 전용, P4 는 두 모듈 모두).

- sdk-java: 외부로 나가는 jar. 주석 없는 공개 타입(예외 클래스, `IdTokenClaims`, `ApiResponseEnvelope` 등)에 SDK 사용자에게 필요한 만큼만. 모노레포·서버 내부 사정을 쓰지 않는다.
- rp-app: 고객사 샘플로 이미 정리됐다. 읽고 확인만 한다. 틀린 주석을 발견한 경우가 아니면 바꾸지 않는다. 변경 0 이어도 된다.

- [ ] P1 ~ P6 수행 (rp-app 변경이 없으면 sdk-java 만 커밋)

### Task 12: 전체 검증

- [ ] **Step 1: 전 범위 동일성**

```bash
python3 .claude/tools/comment_check.py selftest
python3 .claude/tools/comment_check.py verify 99a3c3e4808c15f0e17c9b9894736835a8416df0
git diff --stat 99a3c3e4808c15f0e17c9b9894736835a8416df0 | tail -1
git status --short        # 비어 있어야 한다
```
Expected: `SELFTEST OK`, `OK: code identical`, 작업 트리 깨끗.

- [ ] **Step 2: 전 모듈 컴파일**

```bash
./gradlew compileJava compileKotlin compileTestJava compileTestKotlin --console=plain -q
```
Expected: exit 0.

- [ ] **Step 3: 소스를 읽는 테스트** — `./gradlew :admin-app:test --tests '*CsvWriterTest' --console=plain -q` → exit 0.

- [ ] **Step 4: 남은 흔적 후보 전수 확인** — 공통 절차 P1 의 첫 grep 을 여섯 모듈 `src/main` 전체에 돌려, 남은 줄을 하나씩 "도메인 용어 / 의도적으로 남김(이유)" 로 분류한다. 분류 못 하는 것이 있으면 고친다.

- [ ] **Step 5: 문체 grep** — 추가된 줄(`git diff BASE | grep '^+'`)에서 `합니다`, `입니다`, `=====`, `-----`, `NOTE:`, `IMPORTANT:`, `주의:`, `보장`, `설계되었` 를 찾아 금지 목록에 해당하면 고친다.

- [ ] **Step 6: 브랜치 전체 리뷰** — Review Focus 5개 항목으로 전체 diff 를 본다. 고친 것이 있으면 Step 1·2 를 다시 돌리고 `docs: 주석 정비 최종 검토 반영` 으로 커밋.

- [ ] **Step 7: main 머지** — 메인 저장소에서 `git merge --no-ff docs/newcomer-comments`. push 하지 않는다.
