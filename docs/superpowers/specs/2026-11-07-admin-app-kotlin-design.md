# admin-app Kotlin 전환 (3단계) — 설계

- 작성일: 2026-11-07
- 상태: 확정 (사용자 "3단계 진행해줘", 2026-11-07, builder check 반영 — 사용자 결정 필요 항목 없음)
- 선행: 1단계 Boot 4.1.2(main `7dd0e4b8`), 2단계 passkey-app Kotlin(main `011b8807`).
  상위 로드맵: `docs/superpowers/specs/2026-11-06-spring-boot-4-kotlin-design.md` §8.
- 2단계 설계를 그대로 계승한다: `docs/superpowers/specs/2026-11-06-passkey-app-kotlin-design.md`.
  전환 패턴·함정은 `docs/superpowers/notes/kotlin-conversion-patterns.md`. 이 문서는 **admin-app 에 새로 생기는 것만** 적는다.

## 1. 의도와 이유
- 사용자는 커리어를 위해 Kotlin 을 원하고, 목표는 **Spring 실무 정석 Kotlin**(코루틴·Kotest·MockK 제외, 사용자 결정).
- 2단계에서 빌드 기반과 전환 패턴을 확정했다. 3단계는 그 패턴으로 가장 큰 모듈 admin-app 을 옮긴다.

## 2. 목표 / 비목표 / 반목표

### 목표
- admin-app 본 코드(158파일 ≈18.8k줄)와 테스트(135파일 ≈30.7k줄)를 **전부 Kotlin**(Java 파일 0).
- 2단계와 같은 빌드 설정(Kotlin 2.4.21, all-open, jackson-module-kotlin, mockito-kotlin).

### 비목표
- core·webauthn·sdk-java·rp-app 언어 변경, 루트 Lombok 스코핑(백로그 유지).
- Thymeleaf 템플릿·정적 JS/CSS 변경(§4-1 의 접근자 호환에 필요한 최소 수정만 허용하고 기록).
- 기능 추가·버그 수정·리팩터링(전환으로 자연스럽게 정리되는 관용화만).

### 반목표 (동작해도 실패)
- **화면·HTTP 동작 변화**: 페이지 HTML(렌더 결과), htmx 부분 응답·`HX-*` 헤더, 리다이렉트·상태 코드, 폼 검증 메시지,
  CSV/PDF 다운로드 내용, JSON 응답, 감사 체인 JSON(`AuditChainJacksonCompatTest` 픽스처, 수정 금지),
  `AppJsContractTest` 계약 중 무엇이라도 바뀌면 실패.
- **보안 동작 변화**: 로그인·MFA·복구 코드·CSRF·세션 고정 방지·`@PreAuthorize`(51곳) 권한 판정·
  `TenantBoundary` 테넌트 격리(ArchUnit `TenantBoundaryArchTest` 포함)·비밀번호 정책·MFA 시크릿 암호화·감사 기록.
- **스케줄러·트랜잭션 변화**: `@Scheduled`(6), `@Transactional`(57) 경계·readOnly·프록시 적용 여부.
- 2단계 반목표(null 안전성의 거짓 약속, "문법만 옮긴 Kotlin")도 그대로.

## 3. 결정 사항 (2단계 계승 + admin 고유)
1. 스타일·빌드·테스트 도구·순서는 2단계 §3 그대로. 순서: Java 특성 테스트 보강 → 본 코드 패키지별 전환(Java 테스트를 안전망,
   수정은 접근자 이름·import 만) → 테스트 전환 → de-interop(패턴 메모 §2-5) → 문서.
2. **세션 직렬화**: admin 은 Spring Session Redis(JDK 직렬화). Kotlin 으로 바꾼 클래스가 세션에 들어가면(`AdminUserDetails` 등)
   직렬화 형식이 바뀐다. 1단계에서 **배포 시 admin 전원 재로그인**은 이미 사용자 승인·배포 절차에 반영됐고 1단계가 아직 출하 전이므로
   같은 배포에 묶는다(추가 비용 없음). 대신 **세션에 들어가는 Kotlin 클래스가 직렬화 가능한지**를 테스트로 고정한다
   (JDK 직렬화 왕복, `serialVersionUID` 는 원 Java 에 선언된 경우에만 유지).
3. 요청 바인딩: admin 은 대부분 `@ModelAttribute` 폼 바인딩이다. 폼 객체(record)를 Kotlin 으로 옮길 때 누락·빈 값의 바인딩·검증 결과
   (`BindingResult` 오류 코드, 화면 메시지)가 바뀌지 않게 **검증 대상 필드는 nullable + 같은 제약**(2단계 §6-6 과 같은 원칙).
   기본형(`int`/`boolean`) 컴포넌트는 Kotlin 에서도 non-null 기본형으로 둬 바인딩 실패 동작(typeMismatch)을 유지한다.
4. 작업 단위: worktree 1개·브랜치 1개(`feat/admin-app-kotlin`), 끝난 뒤 main 에 한 번 `--no-ff` 머지. 각 커밋에서 `:admin-app:test` 녹색.
   회귀 판정은 base(main) 대비 새 실패 0(admin 슬라이스/IT 의 pre-existing 실패 여부는 Task 1 에서 base 로 측정).

## 4. admin 고유 함정
1. **Thymeleaf/SpEL 접근자**: 템플릿은 `${row.name}` 프로퍼티 문법을 쓴다(조사 결과 `x.foo()` 메서드형 레코드 접근 없음). Kotlin `data class`
   는 `getName()` 을 만들어 그대로 동작한다. 위험은 **`is` 로 시작하는 Boolean 컴포넌트**(record `isActive()` → Kotlin `val isActive` 는 `isActive()`
   getter 라 SpEL `x.isActive`/`x.active` 해석이 달라질 수 있음)와 `get`/`is` 접두사가 붙은 일반 메서드. 해당 컴포넌트는 템플릿 사용처를 grep 하고
   렌더 테스트로 확인한다. `fmt.xdata(...)`(vararg) 등 템플릿이 호출하는 헬퍼 빈 메서드 시그니처는 그대로 둔다.
2. **SpEL 파라미터 이름**: `@PreAuthorize` 가 `#param` 을 참조하면 `javaParameters = true` 로 이름이 보존돼야 한다(빌드 설정에 포함).
3. **ArchUnit**: `TenantBoundaryArchTest` 는 클래스 단순 이름으로 대상을 찾고 `TenantBoundary` 의존을 요구한다. 이름·패키지 유지.
   Kotlin 이 만드는 `FooKt` 파일 클래스·companion 은 대상 아님.
4. **Lombok**: `@RequiredArgsConstructor` 9개·`@Slf4j` 44개. Kotlin 은 아직 Java 인 클래스의 Lombok 생성 멤버를 못 본다 —
   Kotlin 코드가 그 생성자/로거를 직접 쓰면 같은 Task 에서 함께 전환(Spring 주입만 하면 무관).
5. **독립 매퍼**: `StandaloneJson`(7곳)은 손으로 만든 Jackson 3 매퍼(파서 상한 직접 설정). Kotlin 모듈 미등록 상태를 유지하고, 여기에
   넘기는 Kotlin 클래스는 생성자 creator 가 인식되는지(또는 Map/JsonNode 만 넘기는지) 확인한다.
6. **PDF/CSV**: openhtmltopdf 감사 보고서와 CSV 내보내기는 바이트 비교가 어렵다 — 텍스트 내용(PDF 는 추출 텍스트, CSV 는 전체 문자열) 특성 테스트가
   없으면 Task 1 에서 추가.

## 4A. 안전망 보강 (Task 1, 전환 전 Java 상태에서 작성·동결 — builder check 반영, Claude 결정)
1. **렌더 HTML 스냅샷**: 기존 jsoup 부분 단언만으로는 "렌더 결과 불변"을 판정 못 한다. 각 페이지 컨트롤러의 주 GET 과 htmx 부분 응답
   (탭·행 fragment)을 슬라이스 테스트(서비스는 결정적 스텁)로 렌더해 **정규화된 전체 HTML** 을 `src/test/resources/contract/html/**` 에
   기록·비교한다(1단계 `contract.record` 모드 재사용). 정규화: CSRF 토큰·CSP nonce·현재 시각·랜덤 ID 만 치환, 나머지 바이트 그대로.
   대상은 화면 컨트롤러 전부(대표 1개가 아니라 컨트롤러당 최소 주 GET 1개 + fragment 1개). 뷰 모델이 Kotlin 으로 바뀌어 템플릿 해석이
   달라지면(§4-1) 여기서 잡힌다.
2. **JSON 형태 스냅샷**: admin 의 모든 `@RestController`/`@ResponseBody` 엔드포인트(사용처 여부와 무관 — 노출돼 있으면 계약)에
   1단계 `JsonShape` 방식 형태 스냅샷을 추가한다. jackson-module-kotlin 등록의 영향을 판정한다.
3. **세션 직렬화**: 세션에 들어가는 객체(`AdminUserDetails`, 활성 테넌트 세션 속성, 그 밖에 `session.setAttribute`/`@SessionAttributes`
   대상 전수)를 JDK 직렬화 왕복하는 테스트. 구·신버전 공존(롤링 배포)은 없다 — 배포는 단일 admin 컨테이너 교체이고 1단계(Security 7)가 이미
   세션 비호환을 만들었다. 1~3단계는 아직 출하 전이며 같은 배포로 나간다(qa-bundle 재생성은 백로그 §0 출하 단계). 세션 정리 절차는 1단계 문서에 이미 있음.
4. **CSV/PDF**: 전체 문자열(CSV)·추출 텍스트(PDF) 특성 테스트가 없으면 추가.
5. **base 상태**: 1단계에서 admin 슬라이스 테스트 14클래스를 복구해 base 는 0 실패가 기대된다. Task 1 에서 base 실행으로 실패 집합을
   측정·기록하고, pre-existing 실패가 있으면 수정하지 않고(비목표) 그 테스트가 지키던 동작을 위 스냅샷으로 덮는다.

## 5. 검증 (완료 조건)
1. 각 Task 커밋에서 `:admin-app:test` 의 실패 집합이 base 와 같거나 작다(새 실패 0). 픽스처·계약 테스트 무수정.
2. `:passkey-app:test`, `:rp-app:test` 녹색(공유 빌드 영향 없음).
3. 본 코드 전환 동안 Java 테스트는 접근자 이름·import 수정만(커밋 메시지에 기록).
4. 로컬 기동: admin 로그인(+MFA) → 테넌트 목록·상세 탭·설정·감사 체인 화면 열람, 1회 쓰기(예: API Key 발급/폐기)를 브라우저로 확인.
5. Docker 이미지(admin-app) amd64 빌드 성공.
6. Task 별 claude+codex 3-gate, 최종 전체 리뷰는 **브랜치 base Java 와 직접 대조**(2단계 교훈).

## 6. Claude 가 정한 사항 (사용자 검토용)
1. 테스트 3.1만 줄까지 전부 Kotlin(목표 "Java 0" 을 2단계와 동일하게 적용).
2. 세션 직렬화 형식 변경을 1단계 재로그인 배포에 묶음(§3-2).
3. 폼 객체 nullable 원칙(§3-3).
4. 브랜치 1개·머지 1회(§3-4).
5. 템플릿 수정은 접근자 호환 최소 수정만, 기록 의무(§2 비목표).
6. 안전망 보강 범위(§4A): 화면 컨트롤러 전부의 렌더 HTML 스냅샷 + 모든 JSON 엔드포인트 형태 스냅샷 + 세션 직렬화 왕복.
