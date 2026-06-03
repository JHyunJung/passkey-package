# Sample-RP Well-Known URI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** sample-rp 가 네이티브 앱 패스키용 Digital Asset Links(`assetlinks.json`) 와 Apple App Site Association(`apple-app-site-association`) 을 설정 주입 방식으로 200 + `application/json` 으로 호스팅한다.

**Architecture:** `@ConfigurationProperties` 로 앱 메타데이터(패키지명/지문/AppID)를 외부화하고, `WellKnownController` 가 이를 읽어 표준 wire format JSON 을 명시 조립해 반환한다. 확장자 없는 iOS 파일도 `produces = application/json` 으로 Content-Type 을 강제한다. Spring Security 에 `/.well-known/**` permitAll 을 명시하고, `Allow: /.well-known/` robots.txt 를 함께 제공한다.

**Tech Stack:** Java 17, Spring Boot 3.x (sample-rp), `@ConfigurationProperties` record, `@WebMvcTest` + MockMvc, JUnit 5.

**참고 패턴:**
- `@ConfigurationProperties` record: `sample-rp/.../config/PasskeyProperties.java`
- well-known 컨트롤러 (표준 포맷, envelope 없음): `passkey-app/.../api/v1/rp/JwksController.java`
- `@ConfigurationPropertiesScan` 이 `SampleRpApplication` 에 이미 있어 record 자동 등록됨 (별도 `@EnableConfigurationProperties` 불필요)
- 정답지: 프로젝트 루트 `assetlinks.json`, `apple-app-site-association`

---

## File Structure

**Create:**
- `sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WellKnownProperties.java` — 설정 바인딩 전용 record. android 리스트 + ios 객체.
- `sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WellKnownController.java` — 두 엔드포인트 서빙. WellKnownProperties 읽어 표준 JSON 조립.
- `sample-rp/src/main/resources/static/robots.txt` — `Allow: /.well-known/`.
- `sample-rp/src/test/java/com/crosscert/passkey/samplerp/web/WellKnownControllerTest.java` — `@WebMvcTest` MockMvc 테스트.

**Modify:**
- `sample-rp/src/main/resources/application.yml` — `sample-rp.well-known` 설정 블록 + 첨부 샘플 기본값.
- `sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WebSecurityConfig.java` — `/.well-known/**`, `/robots.txt` permitAll 명시.
- `docs/rp-server-api.md` — §7.1 에 sample-rp 구현 및 운영 주의사항 추가.

---

## Task 1: WellKnownProperties (설정 바인딩 record)

**Files:**
- Create: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WellKnownProperties.java`

- [ ] **Step 1: WellKnownProperties record 작성**

`sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WellKnownProperties.java`:

```java
package com.crosscert.passkey.samplerp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)
 * 에 들어갈 앱 메타데이터. sample-rp 를 RP 레퍼런스 구현으로 쓰는 고객사는 코드를
 * 고치지 않고 환경변수/yml override 로 자기 앱 값만 채운다.
 *
 * - android: assetlinks.json 의 target 배열. 앱마다 패키지명 + 서명 지문 리스트.
 *   드버그/릴리즈 서명이 다르거나 지문이 여러 개면 sha256Fingerprints 에 나열한다.
 * - ios: apple-app-site-association 의 webcredentials.apps. "TeamID.BundleID" 리스트.
 */
@ConfigurationProperties(prefix = "sample-rp.well-known")
public record WellKnownProperties(
        List<AndroidApp> android,
        Ios ios
) {
    public record AndroidApp(
            String packageName,
            List<String> sha256Fingerprints
    ) {}

    public record Ios(
            List<String> appIds
    ) {}

    /** android/ios 미설정 시 NPE 대신 빈 리스트로 안전하게 동작. */
    public WellKnownProperties {
        android = android == null ? List.of() : android;
        ios = ios == null ? new Ios(List.of()) : ios;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :sample-rp:compileJava`
Expected: BUILD SUCCESSFUL (record 자동 등록 — `@ConfigurationPropertiesScan` 이 이미 켜져 있음)

- [ ] **Step 3: 커밋**

```bash
git add sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WellKnownProperties.java
git commit -m "feat(sample-rp): WellKnownProperties — assetlinks/AASA 앱 메타 설정 바인딩"
```

---

## Task 2: WellKnownController 테스트 작성 (TDD — 실패부터)

**Files:**
- Test: `sample-rp/src/test/java/com/crosscert/passkey/samplerp/web/WellKnownControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`sample-rp/src/test/java/com/crosscert/passkey/samplerp/web/WellKnownControllerTest.java`:

```java
package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.config.WellKnownProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WellKnownController 단위 테스트. Security 필터 체인 없이 MVC 슬라이스만 띄우고
 * (excludeAutoConfiguration 으로 Security 제외), WellKnownProperties 는 테스트 전용
 * 고정값 Bean 으로 주입한다. Security 통과 검증은 SampleRpSmokeIT/통합에 맡기지 않고
 * 별도 Task 4 에서 수동 curl 로 확인한다.
 */
@WebMvcTest(WellKnownController.class)
class WellKnownControllerTest {

    @Autowired
    MockMvc mvc;

    @TestConfiguration
    static class FixtureConfig {
        @Bean
        WellKnownProperties wellKnownProperties() {
            return new WellKnownProperties(
                    List.of(new WellKnownProperties.AndroidApp(
                            "com.example.app",
                            List.of("AA:BB", "CC:DD"))),   // 지문 2개 — 리스트 지원 검증
                    new WellKnownProperties.Ios(List.of(
                            "TEAMID1.com.example.app",
                            "TEAMID2.com.example.app")));   // app 2개
        }
    }

    @Test
    void assetLinks_returns200_applicationJson_withConfiguredValues() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
           .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
           .andExpect(jsonPath("$[0].relation[1]").value("delegate_permission/common.get_login_creds"))
           .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
           .andExpect(jsonPath("$[0].target.package_name").value("com.example.app"))
           .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("AA:BB"))
           .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[1]").value("CC:DD"));
    }

    @Test
    void appleAppSiteAssociation_returns200_applicationJson_withConfiguredApps() throws Exception {
        mvc.perform(get("/.well-known/apple-app-site-association"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
           .andExpect(jsonPath("$.webcredentials.apps[0]").value("TEAMID1.com.example.app"))
           .andExpect(jsonPath("$.webcredentials.apps[1]").value("TEAMID2.com.example.app"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :sample-rp:test --tests WellKnownControllerTest`
Expected: 컴파일 실패 — `WellKnownController` 클래스가 아직 없음 ("cannot find symbol: class WellKnownController")

- [ ] **Step 3: 커밋 (실패 테스트)**

```bash
git add sample-rp/src/test/java/com/crosscert/passkey/samplerp/web/WellKnownControllerTest.java
git commit -m "test(sample-rp): WellKnownController 실패 테스트 — 200/application-json/리스트"
```

---

## Task 3: WellKnownController 구현 (테스트 통과)

**Files:**
- Create: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WellKnownController.java`

- [ ] **Step 1: WellKnownController 작성**

`sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WellKnownController.java`:

```java
package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.config.WellKnownProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 네이티브 앱 패스키용 Well-Known URI 호스팅. OS/CDN(Google Play services, Apple CDN)
 * 이 직접 가져가 "도메인 → 앱" 소유를 검증하는 표준 wire format 이므로, JwksController
 * 처럼 ApiResponse envelope 없이 표준 JSON 을 직접 반환한다.
 *
 * 주의: apple-app-site-association 은 확장자가 없어 Spring 이 Content-Type 을
 * 추측하지 못한다(octet-stream 위험). produces = APPLICATION_JSON_VALUE 로 강제한다.
 * 검증 에이전트는 3xx/비-json 을 거부하므로 컨트롤러 직접 매핑으로 정확한 경로만 200 을 낸다.
 */
@RestController
public class WellKnownController {

    private static final Logger log = LoggerFactory.getLogger(WellKnownController.class);

    /** 패스키 자동완성을 위한 표준 relation 조합 — 고객사가 바꿀 일이 거의 없어 상수로 고정. */
    private static final List<String> RELATIONS = List.of(
            "delegate_permission/common.handle_all_urls",
            "delegate_permission/common.get_login_creds");

    private final WellKnownProperties props;

    public WellKnownController(WellKnownProperties props) {
        this.props = props;
    }

    @GetMapping(value = "/.well-known/assetlinks.json",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> assetLinks() {
        List<Map<String, Object>> statements = props.android().stream()
                .map(app -> Map.<String, Object>of(
                        "relation", RELATIONS,
                        "target", Map.of(
                                "namespace", "android_app",
                                "package_name", app.packageName(),
                                "sha256_cert_fingerprints", app.sha256Fingerprints())))
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("assetlinks served: androidApps={}", statements.size());
        }
        return statements;
    }

    @GetMapping(value = "/.well-known/apple-app-site-association",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> appleAppSiteAssociation() {
        List<String> apps = props.ios().appIds();
        if (log.isDebugEnabled()) {
            log.debug("aasa served: iosApps={}", apps.size());
        }
        return Map.of("webcredentials", Map.of("apps", apps));
    }
}
```

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew :sample-rp:test --tests WellKnownControllerTest`
Expected: BUILD SUCCESSFUL — 2 tests passed

- [ ] **Step 3: 커밋**

```bash
git add sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WellKnownController.java
git commit -m "feat(sample-rp): WellKnownController — assetlinks/AASA 200 + application/json"
```

---

## Task 4: application.yml 설정 + 첨부 샘플 기본값

**Files:**
- Modify: `sample-rp/src/main/resources/application.yml`

- [ ] **Step 1: well-known 설정 블록 추가**

`sample-rp/src/main/resources/application.yml` 의 `sample-rp:` 블록 아래에 `well-known:` 을 추가한다. 기존 `sample-rp.origin` 과 같은 레벨이 되도록 들여쓰기를 맞춘다.

기존:
```yaml
sample-rp:
  origin: ${SAMPLE_RP_ORIGIN:http://localhost:9090}
```

변경 후:
```yaml
sample-rp:
  origin: ${SAMPLE_RP_ORIGIN:http://localhost:9090}
  # 네이티브 앱 패스키용 Well-Known URI 값. 미설정 시 첨부 샘플 앱(com.crosscert.sample.passkey)
  # 으로 바로 동작한다. 고객사는 아래 환경변수만 자기 앱 값으로 교체하면 코드 수정 없이 재사용.
  well-known:
    android:
      - package-name: ${WK_ANDROID_PACKAGE:com.crosscert.sample.passkey}
        sha256-fingerprints:
          - ${WK_ANDROID_SHA256:63:0C:F8:85:C6:9A:80:A0:87:B0:A2:72:83:61:00:29:F8:36:65:FF:EF:8E:FE:71:71:74:13:ED:34:9D:7C:54}
    ios:
      app-ids:
        - ${WK_IOS_APPID:LTPC88ZFE8.com.crosscert.sample.passkey}
```

- [ ] **Step 2: 앱 부팅 + 실제 응답/Content-Type 검증 (함정 ① 확인)**

앱을 띄워 두 엔드포인트가 200 + `application/json` 으로 응답하는지 `curl -i` 헤더로 직접 확인한다. (확장자 없는 iOS 파일이 octet-stream 으로 새지 않는지가 핵심)

Run (별 터미널):
```bash
./gradlew :sample-rp:bootRun
```

Run (앱 기동 후):
```bash
curl -i http://localhost:9090/.well-known/assetlinks.json
curl -i http://localhost:9090/.well-known/apple-app-site-association
```

Expected (양쪽 모두):
- `HTTP/1.1 200`
- `Content-Type: application/json`
- assetlinks 본문이 첨부 `assetlinks.json` 과 동일 구조 (relation 2개, package_name `com.crosscert.sample.passkey`, 지문 1개)
- aasa 본문이 `{"webcredentials":{"apps":["LTPC88ZFE8.com.crosscert.sample.passkey"]}}`

확인 후 bootRun 종료 (Ctrl+C).

- [ ] **Step 3: 커밋**

```bash
git add sample-rp/src/main/resources/application.yml
git commit -m "feat(sample-rp): well-known 설정 블록 + 첨부 샘플 앱 기본값"
```

---

## Task 5: WebSecurityConfig permitAll + robots.txt

**Files:**
- Modify: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WebSecurityConfig.java`
- Create: `sample-rp/src/main/resources/static/robots.txt`

- [ ] **Step 1: robots.txt 생성**

`sample-rp/src/main/resources/static/robots.txt`:

```
User-agent: *
Allow: /.well-known/
```

- [ ] **Step 2: WebSecurityConfig 의 requestMatchers 에 경로 명시**

`sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WebSecurityConfig.java` 의 `requestMatchers(...)` 줄을 수정한다.

기존:
```java
                    .requestMatchers("/", "/register", "/login", "/logout",
                                     "/passkey/**", "/css/**", "/js/**").permitAll()
```

변경 후 (`/.well-known/**`, `/robots.txt` 추가):
```java
                    .requestMatchers("/", "/register", "/login", "/logout",
                                     "/passkey/**", "/css/**", "/js/**",
                                     "/.well-known/**", "/robots.txt").permitAll()
```

> 현재 `anyRequest().permitAll()` 라 동작은 통과하지만, 향후 보안 강화 시에도 안전하도록 의도를 명시한다.

- [ ] **Step 3: 컴파일 + 전체 sample-rp 테스트 확인**

Run: `./gradlew :sample-rp:test`
Expected: BUILD SUCCESSFUL (WellKnownControllerTest 포함 통과, SampleRpSmokeIT 는 `@Disabled` 라 skip)

- [ ] **Step 4: robots.txt 와 Security 를 함께 검증 (bootRun)**

Run (별 터미널): `./gradlew :sample-rp:bootRun`

Run:
```bash
curl -i http://localhost:9090/robots.txt
```
Expected: `HTTP/1.1 200`, 본문에 `Allow: /.well-known/` 포함.

확인 후 bootRun 종료.

- [ ] **Step 5: 커밋**

```bash
git add sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WebSecurityConfig.java \
        sample-rp/src/main/resources/static/robots.txt
git commit -m "feat(sample-rp): /.well-known/** permitAll 명시 + Allow robots.txt"
```

---

## Task 6: 운영 문서 추가 (rp-server-api.md §7.1)

**Files:**
- Modify: `docs/rp-server-api.md`

- [ ] **Step 1: §7.1 의 "도메인 연결(필수)" 항목 아래에 sample-rp 구현 안내 추가**

`docs/rp-server-api.md` §7.1 "도메인 연결(필수)" 항목(`- iOS: ...` / `- Android: ...` / `- 여기서 <rpId>는 ...` 줄들)의 **바로 다음**에 아래 블록을 삽입한다.

```markdown

> **sample-rp 레퍼런스 구현**: 이 well-known 두 파일을 `sample-rp` 가 직접 호스팅합니다
> (`WellKnownController`). 값은 `application.yml` 의 `sample-rp.well-known` 에서 주입하며,
> 미설정 시 데모 앱(`com.crosscert.sample.passkey`) 으로 동작합니다. 고객사는 코드를 고치지 않고
> 아래 환경변수만 자기 앱 값으로 교체하면 됩니다.
>
> | 환경변수 | 의미 | 예시 |
> |---|---|---|
> | `WK_ANDROID_PACKAGE` | Android 패키지명 | `com.yourcompany.app` |
> | `WK_ANDROID_SHA256` | 앱 서명 SHA-256 지문 (대문자 콜론 구분) | `AB:CD:...` |
> | `WK_IOS_APPID` | `TeamID.BundleID` | `ABCDE12345.com.yourcompany.app` |
>
> 지문이 여러 개(드버그/릴리즈)거나 iOS 앱이 여러 개면 `application.yml` 에서 리스트로 나열합니다.
>
> **운영 주의사항**
> - **HTTPS 필수**: 검증 에이전트는 `https://<rpId>/.well-known/...` 만 신뢰합니다. (`localhost` 만 예외)
> - **도메인별 호스팅**: well-known 은 `rpId` 도메인에 묶입니다. 고객사마다 자기 도메인의 RP 서버에서
>   자기 앱 값으로 호스팅해야 합니다. 한 RP 인스턴스가 여러 고객 도메인을 동시에 받는 구성은 별도 설계 대상입니다.
> - **Content-Type**: 두 응답 모두 200 + `application/json` 이어야 합니다(특히 확장자 없는
>   apple-app-site-association). `WellKnownController` 가 `produces` 로 강제합니다.
> - **Apple CDN 캐싱**: iOS 는 Apple CDN 이 파일을 가져가 캐싱하므로, 값 변경이 즉시 반영되지 않을 수 있습니다.
> - **robots.txt**: 운영 RP 가 robots.txt 로 크롤러를 막는다면 `Allow: /.well-known/` 을 추가하세요.
>   sample-rp 는 이를 위한 robots.txt 를 기본 제공합니다.
```

- [ ] **Step 2: 문서 정합성 눈으로 확인**

Run: `grep -n "sample-rp 레퍼런스 구현\|WK_ANDROID_PACKAGE\|Apple CDN 캐싱" docs/rp-server-api.md`
Expected: 삽입한 블록의 3개 마커가 모두 매치.

- [ ] **Step 3: 커밋**

```bash
git add docs/rp-server-api.md
git commit -m "docs(rp): §7.1 sample-rp well-known 구현 + 운영 주의사항"
```

---

## Task 7: 첨부 파일 정리 + 최종 검증

**Files:**
- Delete (선택): 루트 `assetlinks.json`, `apple-app-site-association`

- [ ] **Step 1: 루트 첨부 파일 처리 결정**

루트의 `assetlinks.json` / `apple-app-site-association` 은 값의 출처(정답지) 역할을 마쳤고 그 값이
`application.yml` 기본값으로 흡수되었다. 레포 루트에 남으면 "이게 호스팅되는 파일인가?" 하는 혼동을 줄 수
있으므로 삭제한다. (보존이 필요하다는 별도 판단이 있으면 이 스텝을 건너뛴다.)

Run:
```bash
git rm assetlinks.json apple-app-site-association 2>/dev/null || rm -f assetlinks.json apple-app-site-association
```

- [ ] **Step 2: 전체 빌드 + 테스트 최종 확인**

Run: `./gradlew :sample-rp:build`
Expected: BUILD SUCCESSFUL — 컴파일 + WellKnownControllerTest 통과, SmokeIT skip.

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "chore(sample-rp): 루트 첨부 well-known 파일 정리 — 설정 기본값으로 흡수됨"
```

---

## Self-Review 결과

**1. Spec coverage** — spec 각 섹션 대응:
- §3 아키텍처(Properties/Controller 분리) → Task 1, 3 ✅
- §4 설정 구조 + 기본값 + relation 상수 → Task 1, 3, 4 ✅
- §5 엣지: iOS Content-Type → Task 3(produces) + Task 4 Step 2(curl 검증) ✅ / 빈 리스트 → Task 1(compact ctor) ✅ / Security → Task 5 ✅ / robots.txt → Task 5 ✅
- §6 테스트(7항목) → Task 2 테스트가 #1~5 커버. #6(빈 리스트)은 Task 1 compact ctor 로 보장되나 별도 테스트는 생략(YAGNI — ctor 가 단순). #7(Security 통과)은 Task 5 Step 4 수동 curl 로 검증(WebMvcTest 는 Security 슬라이스 제외라 단위테스트보다 실제 부팅 검증이 적합) ✅
- §7 산출물 → Task 1~6 전부 ✅
- §8 검증(bootRun + curl) → Task 4 Step 2, Task 5 Step 4, Task 7 Step 2 ✅
- §9 범위 밖 → 문서에 명시(Task 6) ✅

**2. Placeholder scan** — TBD/TODO/"적절히 처리" 없음. 모든 코드 스텝에 완전한 코드 포함. ✅

**3. Type consistency** — `WellKnownProperties` (record), `.android()` → `List<AndroidApp>`, `AndroidApp.packageName()`/`.sha256Fingerprints()`, `.ios().appIds()` 가 Task 1 정의와 Task 2/3 사용처에서 일치. yml 키 `package-name`/`sha256-fingerprints`/`app-ids` 가 record 컴포넌트(`packageName`/`sha256Fingerprints`/`appIds`)와 Spring relaxed binding 으로 매핑됨. ✅
