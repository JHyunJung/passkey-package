# 사용량 정산 장부 설계 (등록·인증 성공 건수)

- 작성일: 2026-11-25
- 상태: 승인 대기
- 표기: **[결정]** 은 사용자가 대화에서 정한 것, **[제안]** 은 작성자가 정한 것으로 사용자가 뒤집을 수 있다.

## 1. 의도

고객사와 **등록 성공 건수·인증 성공 건수 기준의 사용량 과금 계약**이 이미 체결돼 있다 **[결정]**.
어드민에 내부(PLATFORM_OPERATOR)용 사용량 정산 화면을 만들어 다음 세 가지의 근거로 쓴다 **[결정]**.

1. 고객사 청구
2. 계약 갱신 협의
3. 신규 고객사 영업(고객사를 특정하지 않은 전체 추이)

지금 구조는 청구 근거로 쓸 수 없다.

- 성공 건수는 `ceremony_event` 에 남지만, 본 처리가 커밋된 **뒤** 별도 트랜잭션으로 기록하고
  실패하면 경고 로그만 남긴다(`CeremonyEventRecorder.record`, `recordAfterCommit`).
  커밋과 기록 사이에 장애가 나면 성공 응답은 나갔는데 건수에서 빠지고, 빠진 양을 알 수 없다.
- 원본은 90일 뒤 삭제된다(`RetentionPurgeJob`, `passkey.retention.ceremony-event`). 요약 테이블이 없어
  90일 넘는 수치는 사라진다.
- 월 마감 개념이 없어 같은 달의 수치가 조회 시점마다 달라질 수 있다.

## 2. 목표 / 비목표 / 금지

**목표**

- G1. 성공으로 커밋된 등록·인증 건수와 장부 건수가 정의상 일치한다.
- G2. 테넌트·일자(KST)별 건수를 기간 제한 없이 보관한다.
- G3. 월이 끝나면 수치를 마감해 고정하고, 마감 사실과 수치를 감사 로그 해시 체인에 남긴다.
- G4. PLATFORM_OPERATOR 가 월별·테넌트별 수치를 화면에서 보고 CSV 로 받는다.
- G5. 같은 요약에서 전체 월별 추이(영업 자료)를 본다.
- G6. 지금 남아 있는 과거 원본(최대 90일)을 요약에 채우되 이전 방식 집계임을 표시한다.

**비목표**

- 단가·요금 계산, 청구서 발행, 결제. 이 작업은 건수까지만 다룬다.
- RP_ADMIN(고객사 담당자)에게 정산 수치 노출.
- 구축형 고객의 사용량을 본사로 전송하는 기능. 구축형은 고객 서버의 어드민에서 같은 화면의 CSV 를 받는다 **[제안]**.
- 마감된 달의 재개(reopen)·수정 기능. 마감 후 오류가 발견되면 시스템 밖에서 정산 조정한다 **[제안]**.
- 실패 사유 분해, 리텐션 코호트, 클라이언트 환경, API 호출량 집계.
- `credential_auth_event` 의 기록 방식 변경. 이 테이블은 청구 근거가 아니다.

**금지(이렇게 되면 실패)**

- 실제 성공 건수보다 **많이** 청구되는 것.
- 마감된 달의 수치가 조용히 바뀌는 것.
- 인증·등록 성공 경로의 지연이나 DB 연결 사용이 늘어나는 것.

## 3. 접근 방식

| | A. 기록 방식 유지 + 마감 집계 | **B. 같은 트랜잭션 기록 + 마감 집계** | C. Prometheus 메트릭 |
|---|---|---|---|
| 정확성 | 누락 가능, 양을 알 수 없음 | 커밋된 성공과 일치 | 재시작 시 초기화, 테넌트 구분 없음 |
| 변경 범위 | admin-app | passkey-app 성공 경로 + admin-app | 수집 체계 신설 |
| 증빙력 | 약함 | 강함 | 없음 |

**B 를 선택했다 [결정].** A 는 수치가 정확하다고 증명할 수 없고, C 는 증빙이 되지 않는다.
B 는 인증 서버 성공 경로를 고치지만, 성공 기록이 커밋 후 별도 트랜잭션이 아니라 기존 트랜잭션 안의
INSERT 한 건이 된다. 등록 경로는 커밋 후 두 번째 연결을 더 이상 쓰지 않는다. 인증 경로는
`credential_auth_event` 기록이 남아 두 번째 연결을 계속 쓰지만 커밋 후 기록이 두 건에서 한 건으로 준다.

## 4. 설계

### 4.1 장부: `ceremony_event` 성공 행을 같은 트랜잭션에서 기록 **[제안]**

새 테이블을 만들지 않고 기존 `ceremony_event` 의 성공 행(`REGISTRATION_FINISH_OK`,
`AUTHENTICATION_FINISH_OK`)을 장부로 쓴다. action 문자열은 바꾸지 않는다(`CeremonyAction` 주석 참조).

- `CeremonyEventRecorder` 에 **호출 측 트랜잭션에 참여하는** 기록 메서드를 추가한다.
  활성 트랜잭션이 없으면 예외를 던진다(조용한 폴백 금지). 예외를 삼키지 않는다.
  INSERT 는 즉시 flush 한다. flush 하지 않으면 실패가 `finish` 의 try/catch 밖(커밋 시점)에서 터져
  성공 메트릭이 이미 올라간 뒤가 된다(`saveAndFlush` 선례: `RegistrationFinishService`).
- `created_at` 은 passkey-app JVM 시계의 persist 시각이다(`BaseEntity` `@PrePersist`). 커밋 시각이 아니므로
  자정 직전에 기록되고 자정 뒤에 커밋되는 행이 생긴다. 4.4 의 마감 여유 시간은 이 때문이다.
- `ceremony_event` 에는 FK 와 `@Filter` 가 없어 락 잡힌 credential 행과의 경합이나 테넌트 필터 문제가 없다.
- `RegistrationFinishService.finish` 와 `AuthenticationFinishService.finish` 의 성공 기록을
  `recordAfterCommit` 에서 새 메서드로 바꾼다. 두 메서드는 이미 `@Transactional` 이다.
- 결과: 패스키 저장(등록)·signCount 갱신(인증)과 장부 행이 함께 커밋되거나 함께 롤백된다.
  장부 INSERT 가 실패하면 해당 등록·인증도 실패로 응답한다 **[결정: B 선택에 포함]**.
- BEGIN 행(`REGISTRATION_BEGIN`, `AUTHENTICATION_BEGIN`)은 지금처럼 best-effort 로 둔다.
  청구 대상이 아니다.
- `credential_auth_event` 의 SUCCESS 기록은 지금처럼 커밋 후 기록으로 둔다. 따라서 이 테이블의
  성공 수와 장부 수는 드물게 어긋날 수 있고, 청구 기준은 항상 장부다.
- `passkey_ceremony_total` 메트릭은 그대로 둔다.

같은 인증 요청의 재시도는 challenge 가 일회용이라 각각 별개의 성공이거나 실패다. 별도 중복 제거는 하지 않는다.

### 4.2 일별 요약 `usage_daily`

| 컬럼 | 뜻 |
|---|---|
| tenant_id | 테넌트 |
| usage_date | KST 달력일 |
| reg_success, auth_success | 청구 대상 건수 |
| reg_attempt, auth_attempt | BEGIN 건수. **참고용**(best-effort 기록이라 청구에 쓰지 않음). 장기 성공률 추이에 쓴다 **[제안]** |
| basis | `LEDGER`(4.1 적용 이후의 날) / `LEGACY`(이전 방식으로 쌓인 날) |
| aggregated_at | 마지막 집계 시각 |

- PK 는 (tenant_id, usage_date). 삭제하지 않는다(보존 배치 대상 아님).
- 건수가 0 인 날은 행을 만들지 않는다. 없는 행은 0 으로 읽는다.
- 일 경계는 `FunnelService` 와 같은 방식(`TRUNC(created_at)` + `KstTime.dbDay`)을 따른다.
  배포 시 JVM TZ=Asia/Seoul 전제는 기존과 같다.
- `LEDGER` / `LEGACY` 경계는 설정값 `passkey.usage.ledger-start-date`(KST 날짜)로 정한다 **[제안]**.
  운영자가 4.1 이 배포된 **다음 날**을 넣는다. 이 날짜 이전의 날은 `LEGACY` 다.
  값이 없으면 모든 날을 `LEGACY` 로 표시한다(과대 표시보다 과소 표시가 안전).

### 4.3 집계 배치 (admin-app)

기존 `SchedulerLeaseService` 로 단일 실행을 보장한다. 집계는 원본에서 다시 세어 덮어쓰는 방식이라
몇 번을 돌려도 결과가 같다.

- **매시간**: 오늘 포함 최근 3일을 다시 집계해 `usage_daily` 에 반영한다. 마감된 달의 날은 건드리지 않는다.
  오늘 행은 진행 중인 값이다.
- **월 마감**: 4.4.
- **최초 실행(백필)**: 원본에 남아 있는 전 기간을 한 번 집계한다. 한 번만 실행되도록 실행 여부를
  영속화한다(방법은 구현자 판단). `usage_daily` 가 비어 있는지로 판단하지 않는다(사용량이 0 인 설치와 구분 불가).

### 4.4 월 마감

- 테이블 `usage_period_close`: 기간(`YYYY-MM`) 당 한 행. 상태(`CLOSED` / `PARTIAL`), 확정 시각, basis, 전체 합계.
- 테이블 `usage_monthly`: (tenant_id, 기간) 당 한 행. 등록·인증 성공 건수와 참고용 시도 건수.
  그달 건수가 있는 테넌트만 행을 만든다.
- **자동 마감 [제안]**: 배치가 "이번 달 이전인데 아직 마감되지 않은 달"을 발견하면 다음을 한 트랜잭션으로 수행한다.
  1. 그달의 모든 날을 원본에서 다시 집계한다(배치 중단으로 생긴 구멍 보정).
  2. `usage_daily` 를 합산해 `usage_monthly` 와 `usage_period_close` 를 INSERT 한다.
  3. 감사 로그에 `USAGE_MONTH_CLOSED` 를 남긴다. payload 는 기간, basis, 전체 합계, 테넌트별 건수 목록.
     tenant_id 없이 플랫폼 수준 한 건으로 남긴다(테넌트 감사 탭에 노출되지 않도록) **[제안]**.
     감사 기록은 트랜잭션의 **맨 마지막**에 둔다. `AuditLogService.append` 가 전역 체인 락을 커밋까지 쥐므로
     재집계 동안 다른 감사 기록이 막히지 않게 한다. 감사 기록이 실패하면 마감 전체를 롤백한다
     (`RetentionPurgeJob` 처럼 삼키지 않는다).
  마감 시각은 다음 달 1일 02:00 KST 이후 첫 실행이다. 전날 23:59 에 시작해 자정을 넘겨 커밋된 트랜잭션의
  행이 빠지지 않도록 둔 여유다.
- **불변성**: 마감 테이블에는 INSERT 경로만 있다. PK 가 중복 마감을 막는다. 수정·삭제 API 와 화면은 만들지 않는다.
- **검증 표시**: 화면에서 마감된 달을 열 때 `usage_monthly` 수치와 `USAGE_MONTH_CLOSED` payload 를 비교해
  일치/불일치를 표시한다. 감사 로그는 해시 체인이라 사후 변경이 드러난다.
- **부분 월 [제안]**: 판정은 백필 때 한 번만 한다. 규칙은 "그달 1일 00:00 KST 가 `백필 시각 − 원본 보존 기간`
  보다 앞선다"이다. 즉 원본 일부가 이미 삭제됐을 수 있는 달이다. 첫 원본 행의 날짜로 판정하지 않는다
  (신규 설치·신규 고객의 첫 달이 부분으로 잘못 잡히면 청구할 수 없게 된다).
  부분 월은 일별을 채운 뒤 `usage_period_close` 에 `PARTIAL` 행과 `usage_monthly` 행을 넣어 고정하고,
  감사 로그에는 `USAGE_MONTH_PARTIAL` 로 남긴다. 화면에 "부분(원본 삭제됨)"으로 표시하며 청구 근거로 쓰지 않는다.
  백필 이후에 생기는 달은 부분 월이 되지 않는다.
- 월 basis 는 기간 시작일이 `ledger-start-date` 이상이면 `LEDGER`, 아니면 `LEGACY`.

### 4.5 보존 배치 보호

`ceremony_event` 삭제 기준을 "90일 경과" **그리고** "속한 달에 `usage_period_close` 행이 있음
(`CLOSED` 든 `PARTIAL` 이든)"으로 바꾼다. 마감이 실패해 밀린 달의 원본이 먼저 지워지는 일을 막는다.
지금 삭제 조건은 `created_at < cutoff` 하나뿐이다(`CeremonyEventRepository.deleteCreatedBefore`).
다른 테이블의 보존 규칙은 그대로 둔다.

### 4.6 화면

위치: 플랫폼 현황(`/admin/insights`)에 "사용량 정산" 탭 추가. PLATFORM_OPERATOR 전용(기존 페이지 권한과 동일).

```
사용량 정산                              [2026-10 ▾]  [CSV]
상태: 마감 2026-11-01 02:05 · 집계 기준: 장부 · 감사 로그와 일치

테넌트        등록 성공   인증 성공     합계
acme            1,204      48,310    49,514
globex            310       9,022     9,332
initech             0           0         0
전체            1,514      57,332    58,846

월별 추이 (전체)
월        등록 성공   인증 성공   사용 테넌트   인증 성공률(참고)
2026-10     1,514      57,332        2           93.1%
2026-09     1,102      41,870        2           92.4%
```

- 테넌트 목록은 현재 존재하는 모든 테넌트를 보여 주고 건수가 없으면 0 으로 표시한다.
- 진행 중인 달은 "진행 중 · 집계 시각 HH:mm" 으로 표시하고 `usage_daily` 합계를 보여 준다.
- 상태 종류: 진행 중 / 마감 / 부분(원본 삭제됨). 집계 기준: 장부 / 이전 방식.
- CSV: 선택한 달의 테넌트별 행(기간, 테넌트 식별자·이름, 등록 성공, 인증 성공, 집계 기준, 마감 시각)과
  월별 추이. 기존 insights CSV 내보내기 방식을 따른다.
- 인증 성공률은 참고용 시도 건수로 계산하므로 "참고" 라고 표기한다.
- **기존 화면과의 수치 차이 [제안]**: 같은 페이지의 기존 "월간 사용량" 카드와 `kind=monthly`·`kind=tenants`
  CSV 는 `credential_auth_event` 기준이라 장부와 드물게 다를 수 있다(`PlatformInsightsService`).
  기존 집계는 바꾸지 않고, 화면과 CSV 에 "청구 기준 아님 · 사용량 정산 탭 참조" 를 표기한다.
  `InsightsPageController` 의 "과금·라이선스 자료" 주석도 고친다.

### 4.7 스키마 반영 **[제안]**

- 기준 파일 `scripts/full-schema-part2-schema.sql` 에 세 테이블(`usage_daily`, `usage_monthly`,
  `usage_period_close`)과 권한을 추가한다. `scripts/` 의 수동 미러 파일이 있으면 함께 맞춘다.
- 이미 스키마가 올라가 있는 DB 를 위해 재실행해도 안전한 증분 스크립트를 `scripts/upgrade/` 에 둔다.
  Flyway 제거 뒤 증분 변경의 첫 사례이므로 위치와 이름 규칙은 이 작업에서 정한다.
- 엔티티는 core 에 두고, 앱은 기동 시 검증만 한다(기존 원칙).
- passkey-app 도 core 엔티티를 전부 스캔하고 `ddl-auto: validate` 로 뜬다. 따라서 새 테이블 3개에
  `PSK_APP_RUNTIME` 의 SELECT 권한을 준다(`security_incident` 선례와 같은 이유). 없으면 passkey-app 이
  부팅에 실패한다. `ceremony_event` INSERT 권한은 이미 있다.
- 증분 적용의 기존 관례는 없다(지금까지는 기준 파일 8절에 덧붙이고 기존 DB 는 DBA 가 직접 실행).
- `docs/entity-relationship-diagram.md` 를 함께 갱신한다.

## 5. 제약과 주의

- Oracle SE2 호환(파티셔닝 등 EE 전용 기능 금지).
- 테넌트 격리는 앱 레벨 Hibernate `@Filter` 다. 집계 배치는 전 테넌트를 대상으로 하므로 기존
  플랫폼 집계(`PlatformInsightsService`)가 쓰는 방식을 따른다.
- admin-app `@WebMvcTest` 슬라이스는 새 빈이 생기면 MockBean 누락으로 컨텍스트 로드가 깨진다. 함께 고친다.
- 전체 `./gradlew build` 는 기존 문제로 실패한다. 회귀 판정은 base 와 비교한다.
- passkey-app 의 스레드·풀 예산 설정(`DbPoolThreadBudgetConfigTest`)은 유지한다. BEGIN 행과
  `credential_auth_event` 가 여전히 커밋 후 기록을 쓴다.

## 6. 검증 기준

- 등록·인증 finish 가 롤백되면 장부 행이 남지 않는다.
- 장부 INSERT 가 실패하면 finish 가 실패하고 패스키 저장·signCount 갱신도 남지 않는다.
- 성공 N 건을 실행하면 장부 성공 행이 정확히 N 건이다(동시 실행 포함).
- 같은 날을 여러 번 집계해도 `usage_daily` 가 같다.
- KST 자정 전후 행이 올바른 날·달에 들어간다.
- 마감된 달은 이후 집계 실행으로 바뀌지 않고, 두 번 마감되지 않는다.
- 마감 수치와 감사 로그 payload 가 일치하고, 한쪽을 바꾸면 화면이 불일치를 표시한다.
- 마감되지 않은 달의 원본은 90일이 지나도 보존 배치가 지우지 않는다.
- RP_ADMIN 은 탭과 CSV 에 접근할 수 없다.
- 기존 "월간 사용량" 카드와 CSV 에 "청구 기준 아님" 표기가 나온다.
- 새 테이블이 있는 스키마에서 passkey-app 이 `PSK_APP_RUNTIME` 권한으로 부팅된다.
- Testcontainers(Oracle) 통합 테스트로 확인한다. `CeremonyEvent` 에는 `created_at` 세터가 없으므로
  자정 경계 테스트는 native INSERT 등으로 시각을 지정한다.

## 7. 배포 시 할 일

1. 증분 스크립트 적용(기존 DB) 또는 기준 스키마 적용(신규 DB). **반드시 앱보다 먼저.**
   스크립트 없이 passkey-app 을 먼저 올리면 스키마 검증 실패로 인증 서버가 뜨지 않는다.
2. passkey-app·admin-app 함께 배포.
3. 배포 다음 날짜를 `passkey.usage.ledger-start-date` 에 설정.
4. 첫 배치 실행 후 화면에서 백필 결과 확인.

## 8. 구현자에게 맡기는 것

- 새 기록 메서드의 이름과 트랜잭션 참여 강제 방법.
- 집계 SQL 형태(MERGE 등), 필요 시 `ceremony_event` 인덱스 보강.
- 테이블 컬럼 타입·제약 이름, 엔티티·리포지토리 배치.
- 배치 주기 설정 키 이름과 기본값(위 주기를 기본으로).
- 화면 마크업과 CSV 컬럼 순서(기존 insights 화면 관례를 따름).
- 증분 스크립트의 파일명 규칙과 문서(`deploy/README.md` 등) 반영 위치.
