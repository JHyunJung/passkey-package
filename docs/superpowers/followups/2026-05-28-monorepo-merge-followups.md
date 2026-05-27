# monorepo 통합 Phase — Followups

Phase: monorepo-merge (2026-05-28)
Plan: [docs/superpowers/plans/2026-05-28-monorepo-merge.md](../plans/2026-05-28-monorepo-merge.md)
Spec: [docs/superpowers/specs/2026-05-28-monorepo-merge-design.md](../specs/2026-05-28-monorepo-merge-design.md)

## Manual smoke checklist (main merge 직전 또는 직후)

- [ ] **1. `./gradlew :sdk-java:test :sample-rp:test :admin-app:check`** 모두 BUILD SUCCESSFUL (이미 phase 안에서 입증됨)
- [ ] **2. `:sample-rp:bootRun`** 으로 RP 부팅 → `http://localhost:9090/` 접속 성공
- [ ] **3. dev seed acme-corp API key** 로 등록 → credential 저장 → 로그아웃 → 인증 한 사이클 성공
- [ ] **4. `:sdk-java:publishToMavenLocal`** 명령이 여전히 작동 (외부 publish 필요 시 대비)
- [ ] **5. admin-ui Activity 페이지** 에서 sample-rp 의 audit row 가 보이는지 (이전 phase 의 기능)

## main merge 후 cleanup (사용자 명시적 실행)

**자동화하지 않는다 — 외부 디렉터리 삭제는 사용자가 직접 실행.**

```bash
# 1. tar.gz 백업 (환원 가능 상태 보존)
TS=$(date +%Y%m%d-%H%M%S)
cd /Users/jhyun/Git/10-work/crosscert
tar czf /tmp/sdk-java-pre-monorepo-${TS}.tar.gz sdk-java/
tar czf /tmp/sample-rp-pre-monorepo-${TS}.tar.gz sample-rp/

# 2. 백업 파일 확인
ls -la /tmp/*pre-monorepo*.tar.gz

# 3. 백업 검증 (선택)
tar tzf /tmp/sdk-java-pre-monorepo-${TS}.tar.gz | head -5
tar tzf /tmp/sample-rp-pre-monorepo-${TS}.tar.gz | head -5

# 4. 옛 외부 디렉터리 삭제
rm -rf sdk-java/ sample-rp/

# 5. 확인
ls /Users/jhyun/Git/10-work/crosscert/
# Passkey2/  만 보여야 함
```

## Open regressions (이 phase 범위 밖)

### A. core VpdIsolationIT — Oracle seed/cleanup pollution

`./gradlew :core:test --tests '*VpdIsolationIT*'` 가 9개 fail:
- `ORA-00001: UQ_TENANT_SLUG` (tenant slug unique 충돌)
- `ORA-02292: FK_ADMIN_USER_TENANT` (FK constraint violation)

stash 검증으로 본 phase 의 변경과 무관함을 입증 (parent state에서도 동일 fail).
별도 phase 로 처리 — VPD 테스트의 setup/teardown 이 IT 간 데이터 공유 충돌.

스토리: VpdIsolationIT 는 Phase 7 (tenant-config-normalize) 또는 그 이전 phase
에서 추가된 것으로 보이며, 환경 의존적 IT 일 가능성 — TestContainers 의 fresh
DB 인지, 또는 동일 Oracle instance 위에 다른 IT 시드와 충돌하는지 진단 필요.

### B. PasskeyResponseErrorHandler deprecation 경고

`sdk-java/.../PasskeyResponseErrorHandler.java:26` 의 `handleError(ClientHttpResponse)`
가 Spring 의 deprecation list 에 들어감. break 는 없지만 향후 Spring 메이저 bump
시 fix 필요. 별도 phase 또는 SDK 의 minor refresh 시.

### C. sample-rp/scripts/bootstrap-sample-rp.sh path stale

stale path `cd examples/sample-rp && ./gradlew bootRun` 같은 reference 가 script
안에 남아 있음 (codex review 에서 advisory P2). monorepo 후 `./gradlew :sample-rp:bootRun`
이 정확. 별도 phase 또는 dev-setup followup 으로 처리.

## Deferred items (별도 phase 후보)

1. **sdk-java GitHub publish 자동화** — Maven Central / GitHub Packages 로 외부 publish. 외부 RP 가 SDK 를 의존할 때 필요. 현재는 dogfood 단계라 미루기.
2. **sample-rp Dockerfile** — production-like 환경 테스트 또는 staging 배포 시 필요.
3. **sdk-java multi-language SDK** — Python / Node.js / TypeScript SDK. RP 측 언어 다양화 시.
4. **버전 통일 후속** — sdk-java 가 옛 `0.1.0-SNAPSHOT` 에서 `0.0.1-SNAPSHOT` 으로 강제 이전됨. 향후 첫 stable release 때 `passkey-sdk-java` 단독 versioning 결정 필요.

---
*문서 갱신 책임: 각 followup 시작 시 brainstorm/plan 링크 추가, 완료 시 status [DONE] 표시*
