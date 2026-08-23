# QA 기동 체크리스트

순서대로 진행하고 각 항목을 확인한다. 앞 단계가 끝나지 않으면 다음이 실패한다.

## A. 반입 확인 (서버에 옮긴 직후)

- [ ] `./preflight.sh <OracleIP> 1521` 실행 — `[!!]` 항목 없음
- [ ] `iptables` 설치됨 (없으면 Docker 네트워킹 불가 → ISO 로컬 저장소)
- [ ] SELinux Enforcing 이면 `container-selinux` 설치됨
- [ ] `/var` 여유 20GB 이상
- [ ] Oracle 1521 도달 확인

## B. Docker 설치

- [ ] `sudo ./docker-install/install-docker.sh` 완료
- [ ] `docker info` → Storage `overlay2`, Cgroup `2`
- [ ] `docker compose version` → v2
- [ ] firewalld 활성이면 `docker0` trusted zone 등록

## C. 이미지

- [ ] `./load-images.sh` → 5개 모두 `[OK] … (amd64)`
      (앱 하나만: `./load-images.sh passkey-app`)

## D. DB (이미 생성됨 — 확인만)

스키마·계정·초기 운영자 계정은 생성 완료 전제. 이 번들에 DDL 은 없다.

- [ ] Oracle 1521 도달 확인 (`./preflight.sh <OracleIP> 1521`)
- [ ] 계정 3개 비밀번호 확보
      (`PSK_APP_OWNER` / `PSK_APP_ADMIN_USER` / `PSK_APP_RUNTIME_USER`)
- [ ] **DDL 로 스키마를 미리 만든 경우** `flyway baseline -baselineVersion=4` 처리
- [ ] admin 콘솔 로그인용 운영자 계정이 `admin_user` 에 존재

## E. 설정

- [ ] `./setup.sh` 실행 → 마지막에 `[OK] 검증 통과`

  6가지만 입력하면 된다. 나머지는 유도·자동생성된다.
  - Oracle 호스트 / 포트 / 서비스명
  - 계정 3개 비밀번호
  - passkey · RP 두 FQDN
  - 호스트 내부 IP (`docker0` 자동 제시, 127.0.0.1 은 거부됨)

## F. HTTPS (§9 — 가장 자주 막히는 지점)

- [ ] 내부 DNS 서브도메인 2개 등록 (`passkey-qa.…`, `rp-qa.…`)
- [ ] 앞단 LB/nginx 가 TLS 종료
- [ ] 앞단이 `X-Forwarded-Proto: https` 전달
- [ ] 사설 CA 라면 테스트 브라우저·OS 신뢰 저장소에 등록
      (인증서 경고를 거친 상태로는 WebAuthn 이 동작하지 않음)

## G. 기동

- [ ] `./start.sh` 실행 → `[OK] 전부 정상`
      (Redis → 앱 순서, 프로파일 지정, healthy 대기를 모두 처리한다.
       `RP_API_KEY` 가 비어 있으면 rp-app 은 자동 제외되며 정상이다.)
- [ ] `cd deploy && docker compose logs admin-app` 에 Flyway 성공 로그
      (admin-app 은 `start_period` 90초까지 걸리는 것이 정상)

> compose 를 직접 치는 경우 `COMPOSE_PROFILES=proxy,admin,qa` 를 반드시 지정한다.
> 빠뜨리면 `passkey-app` 하나만 뜨고 나머지가 **에러 없이** 누락된다.

## H. 테넌트 · 키

- [ ] admin 콘솔 로그인 성공
- [ ] 테넌트 생성 — `rpId`=도메인만, `allowedOrigins`=`https://` 포함 전체 origin
- [ ] 첫 테스트는 `mdsRequired=false`
- [ ] API 키 발급 (`registration`, `authentication` scope) — 평문 1회만 표시
- [ ] `./setup.sh --rp-keys` 로 값 2개 입력 후 `./start.sh rp-app`

## I. 검증

- [ ] `https://rp-qa.…` 접속 — 패스키 등록 시 OS 프롬프트 표시
- [ ] 로그아웃 → 패스키로 로그인 성공
- [ ] admin 콘솔 Credentials 목록에 자격증명 표시
- [ ] (MDS 검증 대상이면) `mds_blob_cache` 에 버전 채워진 뒤 `mdsRequired=true`
