# Android 앱 origin(apk-key-hash) 등록·검증 지원 — 설계

날짜: 2026-06-20
상태: 설계 승인됨 (구현 대기)

## 배경 / 문제

WebAuthn의 `clientDataJSON.origin`은 클라이언트 플랫폼에 따라 형태가 다르다.

| 플랫폼 | origin 형태 | RP가 등록할 것 |
| --- | --- | --- |
| 웹 브라우저 | `https://example.com` (scheme+host) | 도메인(URL) |
| Android 네이티브 앱 | `android:apk-key-hash:<base64url(SHA-256(서명인증서))>` | **앱 서명키 해시** (도메인 아님) |
| iOS 네이티브 앱 | `https://<도메인>` (Associated Domains 기반) | 도메인 (웹과 동일) + 서버에 AASA 호스팅 |

현재 코드베이스는 **HTTPS/HTTP URL origin만** 지원한다. 검증 경로 전 계층이 URL을 전제하기 때문에 Android 앱의 `android:apk-key-hash:` origin은 **등록 자체가 불가능**하다.

### 현황 근거 (탐색 결과)

- **origin 검증**: `webauthn/.../clientdata/ClientDataValidator.java:77` — `allowedOrigins.contains(origin)` (정확한 문자열 일치, 와일드카드/파싱 없음). 등록·인증 모두 `NativeWebAuthnVerifier`에서 동일 호출.
- **허용 목록 출처**: DB 테이블 `tenant_allowed_origin` (테넌트별 1:N). 런타임은 `RegistrationFinishService.java:81`에서 `tenant.getAllowedOriginValues()`를 그대로 `Set<String>`으로 넘김.
- **DB CHECK 제약**: `V21__tenant_config_normalize.sql:35-36` — `ck_tao_origin_format` 가 `^https?://...` 정규식으로 URL만 허용. → `android:apk-key-hash:`는 **INSERT 거부**.
- **admin-ui 검증**: `WebauthnConfigTab.tsx:35-59` `validateOrigin()` — `https://`/`http://` scheme만 허용하고 rpId 서브도메인 범위를 강제. → Android 키 입력 불가.
- **백엔드 DTO**: `TenantAdminDto.java:20,32` — `@NotBlank @Size(max=512)`만 검사, 형식은 DB CHECK에 위임.
- **iOS**: origin이 `https://<도메인>`이라 기존 web origin 등록으로 통과. `rp-app .../WellKnownController.kt`에 apple-app-site-association 생성기가 이미 존재.

## 목표

테넌트(RP)가 자신의 Android 앱을 passkey RP로 사용할 수 있도록, `android:apk-key-hash:<해시>` origin을 **등록·검증**할 수 있게 한다. iOS는 기존 도메인 등록으로 커버되므로 점검·문서화만 한다.

## 비목표 (YAGNI)

- assetlinks.json을 런타임에 fetch해 허용 해시를 동적 도출하는 방식(방식 B)은 채택하지 않는다. 런타임 외부 HTTP 의존·SSRF 표면·캐시 복잡도를 들이며, 이 코드베이스의 "외부 런타임 의존 최소화" 방향(MDS/webauthn4j 자체구현)과 어긋난다.
- 키 로테이션 자동화는 하지 않는다. 로테이션은 드물고 수동 해시 추가로 충분하다.
- verifier 코어(`ClientDataValidator`)는 변경하지 않는다.

## 핵심 결정

1. **방식 A — 명시적 origin 등록.** 테넌트가 자기 앱 서명키 해시를 등록하면 `android:apk-key-hash:<해시>` 문자열이 기존 허용 목록에 한 행으로 저장된다. 기존 `contains()` 검증이 그대로 통과시킨다.
2. **데이터 모델 — 기존 `tenant_allowed_origin` 테이블 재사용.** web origin과 android key-hash를 같은 테이블·같은 컬럼(`origin`)에 저장한다. 별도 컬럼/테이블로 분리하지 않는다(엔티티·권한·검증 조합 로직 증가 방지).
3. **입력 UX — SHA-256 지문(hex) 입력 → 자동 변환.** 테넌트는 keytool 출력의 SHA-256 fingerprint(hex)를 붙여넣고, admin-ui가 `android:apk-key-hash:<base64url>`로 변환해 저장한다.

## 형식 명세

### apk-key-hash origin 문자열

```
android:apk-key-hash:<B>
```
- `<B>` = base64url(no padding)로 인코딩한 SHA-256(서명 인증서) 32바이트.
- base64url(32바이트) = padding 없이 **정확히 43자**, 문자셋 `[A-Za-z0-9_-]`.

### SHA-256 지문(hex) → apk-key-hash 변환

입력 예 (keytool `-list` 출력):
```
AB:CD:EF:12:34:...:99      (콜론 구분, 64 hex = 32바이트)
ABCDEF1234...99            (콜론 없이 64 hex 도 허용)
```
변환:
1. 콜론·공백 제거 후 대문자/소문자 무시하고 64 hex 문자인지 검증.
2. hex → 32바이트.
3. base64url(no padding) 인코딩 → 43자.
4. `android:apk-key-hash:` prefix 부착.

## 설계 — 계층별 변경

### 1. DB 스키마 — 새 마이그레이션 `V48`

`ck_tao_origin_format` 제약을 교체해 두 패턴을 모두 허용한다.

```sql
ALTER TABLE tenant_allowed_origin DROP CONSTRAINT ck_tao_origin_format;
ALTER TABLE tenant_allowed_origin ADD CONSTRAINT ck_tao_origin_format CHECK (
  REGEXP_LIKE(origin, '^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$')
  OR REGEXP_LIKE(origin, '^android:apk-key-hash:[A-Za-z0-9_-]{43}$')
);
```

- 제약 이름은 동일하게 유지(`ck_tao_origin_format`)해 재현성 확보.
- 길이를 43자로 못 박아 오입력 차단.
- 기존 행은 전부 https 패턴이라 영향 없음.
- ⚠️ Oracle 제약 변경은 정적 inspection이 못 잡는다. **Testcontainers로 실제 INSERT 성공/거부를 검증**해야 한다(메모: `project_oracle_modify_blob_notnull` 교훈).

### 2. 백엔드 — origin 형식 검증 유틸

DB까지 가서 ORA 에러로 실패하면 사용자 피드백이 나쁘다. 앱 레벨에 가벼운 형식 검증을 추가해 명확한 400을 준다.

- 새 유틸 `AllowedOriginFormat` (admin-app `tenant` 패키지, DB CHECK 정규식과 1:1 대응):
  ```java
  // 두 형태만 허용
  //  1) https?://host[:port]
  //  2) android:apk-key-hash:<43자 base64url>
  static boolean isValid(String origin)
  ```
- `TenantAdminService.create()/update()`에서 각 origin에 대해 호출 → 위반 시 검증 에러(기존 검증 에러 흐름 재사용).
- **verifier 코어 무변경.** `ClientDataValidator`는 여전히 `contains()`로 정확 일치. 저장된 `android:apk-key-hash:...` 문자열이 그대로 비교 대상.
- **런타임 경로 무변경.** `RegistrationFinishService.java:81`이 `getAllowedOriginValues()`를 그대로 넘기므로 Android origin도 자동 포함.

### 3. admin-ui — Android 키 입력 UX

`WebauthnConfigTab.tsx`의 origins 관리 영역을 확장한다.

- 순수 변환 함수 분리: `apkKeyHashFromFingerprint(hex: string): string` — 위 형식 명세대로 변환. 테스트 용이하도록 별도 모듈/export.
- `validateOrigin()` 수정: 입력이 `android:apk-key-hash:` prefix면 별도 분기로 처리.
  - hex 지문 입력 → 변환 → 43자 형식 검증 → 통과.
  - rpId 서브도메인 범위 검사는 **건너뛴다**(앱 서명키는 도메인과 무관).
  - 잘못된 hex 길이/문자는 거부.
- 입력 진입점: origins 섹션에 "Android 앱 추가" 경로(토글 또는 버튼)를 두고 SHA-256 지문 입력칸을 노출. keytool 출력(콜론 포함 64 hex) 붙여넣기를 그대로 받는다.
- 칩(chip) 라벨링: web origin과 시각적으로 구분. Android는 `android:apk-key-hash:Df+m…AbCd` 식으로 prefix 표시 + 해시 일부 truncate.

### 4. iOS — 코드 변경 없음 (점검·문서화)

- iOS origin = `https://<도메인>` → 기존 web origin 등록으로 통과. 추가 코드 불필요.
- `WellKnownController.kt`의 apple-app-site-association 생성기 이미 존재. AASA 호스팅 경로 확인만.
- 본 spec에 "iOS는 도메인 등록 + AASA 호스팅으로 커버됨, 추가 코드 불필요" 명시(완료).

## 테스트 전략

- `apkKeyHashFromFingerprint` 단위 테스트(admin-ui): 알려진 hex 지문 → 기대 base64url 43자, 콜론 유무 양쪽, 잘못된 길이/문자 거부.
- `validateOrigin` 테스트 확장(admin-ui): android 분기가 rpId 범위 검사를 건너뛰고 통과하는지, 잘못된 지문 거부.
- `AllowedOriginFormat.isValid` 단위 테스트(백엔드): 두 패턴 통과/위반 케이스.
- DB CHECK Testcontainers 검증: 실제 Oracle에 `android:apk-key-hash:<43자>` INSERT 성공 + 잘못된 형식(길이/문자/scheme) INSERT 거부.
- origin 검증 통합: `ClientDataValidator`가 저장된 android origin과 정확 일치 시 통과(기존 테스트 패턴 재사용).

## 변경 표면 요약

| 영역 | 변경 | 파일 |
| --- | --- | --- |
| DB | CHECK 제약 교체(https OR android key-hash) | 새 `V48__tenant_allowed_origin_android.sql` |
| 백엔드 검증 | `AllowedOriginFormat` 유틸 + DTO 검증 호출 | admin-app `tenant` 패키지, `TenantAdminService` |
| admin-ui | 지문→apk-key-hash 변환 + 입력 UX + 칩 라벨링 + `validateOrigin` 분기 | `WebauthnConfigTab.tsx` + 변환 유틸 모듈 |
| verifier 코어 | **무변경** | — |
| 런타임 | **무변경** | — |
| iOS | **무변경**, 점검·문서화만 | — |
| 테스트 | 변환/검증/DB CHECK/통합 | 각 모듈 |

## 핵심 원칙

verifier 코어와 런타임은 손대지 않는다. Android origin을 "또 하나의 허용 origin 문자열"로 취급해 변경 표면을 최소화한다. 신규 위험 표면(외부 fetch, 새 검증 경로)을 들이지 않는다.
