# QW-3 Dependency Decisions (2026-05-31)

Phase QW-3 Task 4 — advisory verification before any version bump. Decision rule
(plan §Task 4): bump **only** if a real resolved advisory is fixed by an
**in-range** patch (nimbus: 9.x ≥ 9.41 that 9.40 is vulnerable to; vite: in
`^5.4.x`). Major upgrades (nimbus 10.x, vite 6.x) are explicitly out of QW-3
scope (design §5.4). No blind bumps.

## nimbus-jose-jwt (pinned 9.40, gradle/libs.versions.toml:8; consumed by core/build.gradle.kts:26 + sdk-java/build.gradle.kts:20)

- **Advisory checked:** CVE-2025-53864 / GHSA-xwmg-2g98-w7v9 — "Nimbus JOSE + JWT
  is vulnerable to DoS attacks when processing deeply nested JSON" (uncontrolled
  recursion when deserializing a JWT claim set containing a deeply nested JSON
  object).
- **Affected ranges (per GitHub Advisory Database):**
  - `< 9.37.4` → patched in **9.37.4** (backport on the 9.37 maintenance branch)
  - `>= 9.38-rc1, < 10.0.2` → patched in **10.0.2**
  - **Our pin 9.40 falls in the second range, so it IS technically affected.**
- **Is there an in-9.x forward fix?** No. The only 9.x fix (9.37.4) is a backport
  on the 9.37 branch; for the 9.38+ line (which includes 9.40) the *only* fix is
  **10.0.2 — a major version**. There is no 9.41+/9.x patch resolving this.
- **Sources:**
  - https://github.com/advisories/GHSA-xwmg-2g98-w7v9
  - https://nvd.nist.gov/vuln/detail/CVE-2025-53864
  - https://community.blackduck.com/s/question/0D5Uh00000moS7BKAU/please-update-cve202553864-mapping-for-comnimbusdsnimbusjosejwt-fixed-in-9374-backport-not-only-1002
  - https://security.snyk.io/package/maven/com.nimbusds%3Animbus-jose-jwt
- **Decision: KEEP 9.40 (no bump in QW-3).**
- **Rationale:**
  1. No in-range (9.x ≥ 9.41) fix exists; the forward fix is the major **10.0.2**,
     which is out of QW-3 scope per design §5.4 (no major upgrades in this phase).
     Downgrading to the 9.37.4 backport would *lose* 9.38–9.40 changes and switch
     maintenance lines — also out of scope and not a clean "patch bump".
  2. **Exposure is gated, not open.** The two parse sites that reach the
     vulnerable claim-set deserialization both verify the signature **before**
     calling `getJWTClaimsSet()`:
     - `sdk-java IdTokenVerifier.verify()` — `SignedJWT.parse()` →
       `jwt.verify(verifier)` (RSA against our JWKS) → only then
       `getJWTClaimsSet()`. Tokens are issued by our own `IdTokenIssuer`.
     - `core LicenseVerifier.verify()` — `SignedJWT.parse()` → `jwt.verify(v)`
       (vendor license key) → only then `getJWTClaimsSet()`.
     So the recursion DoS in the claim-set JSON can only be reached by a token
     already signed with our own/vendor key — not an open attacker-controlled
     network input. (`SignedJWT.parse()` itself does header/payload base64 +
     light JSON work pre-verify; the deeply-nested-claim recursion is in the
     claim-set deserialization, which is gated.)
- **Recommended followup (separate phase):** dedicated dependency-upgrade phase
  bumping nimbus-jose-jwt to **≥ 10.0.2** (major) with full regression of
  SigningKeyProviderTest / IdTokenIssuerTest / JwksAssembler / LicenseVerifier
  and the SDK IdTokenVerifier — verifying the 10.x API (JWK/JWS/JWT) is
  source-compatible. Not done here because QW-3 forbids major bumps.

## vite / esbuild (admin-ui devDependency; vite declared `^5.4.6`, resolves to 5.4.21; esbuild 0.21.5 transitive)

- **Advisory checked:** GHSA-67mh-4wv8-2f99 — "esbuild enables any website to
  send any requests to the development server and read the response" (esbuild
  dev server sends `Access-Control-Allow-Origin: *`, letting any origin read
  dev-server responses, e.g. source).
- **Affected / fixed:** esbuild `<= 0.24.2` affected (our 0.21.5 is affected);
  fixed in esbuild **>= 0.25.0**.
- **Is there an in-`^5.4.x` fix?** No. **Vite 5.4.x stays pinned to esbuild
  0.21.5**; the esbuild `>= 0.25.0` bump only landed in **Vite 6** (vitejs/vite
  #19389 / #19428). There is no Vite 5.4.x patch that pulls esbuild 0.25.0.
- **Sources:**
  - https://github.com/advisories/GHSA-67mh-4wv8-2f99
  - https://github.com/vitejs/vite/issues/19428
  - https://github.com/vitejs/vite/discussions/19484
- **Decision: KEEP (no bump in QW-3).**
- **Rationale:**
  1. **Dev-server-only with zero production runtime exposure.** Production serves
     the static `vite build` output: `admin-ui/build.gradle.kts` runs
     `npm run build` → `admin-ui/dist/` and admin-app serves those static assets
     (Spring static serving). The esbuild **dev server** (`vite` / `vite preview`)
     is never run in production; the CORS flaw exists only when a developer runs
     `npm run dev` locally.
  2. No in-range (`^5.4.x`) patch exists; the fix requires **Vite 6 (major)**,
     out of QW-3 scope per design §5.4.
- **Recommended followup (separate phase):** when the admin-ui is next upgraded,
  move to **Vite 6.x** (which brings esbuild >= 0.25.0) and run the full
  `tsc -b && npm test && npm run build` gate (Vite 6 has migration notes for
  the v5 → v6 config changes). Dev-hygiene only; no production risk in the interim.

## Net result

No `gradle/libs.versions.toml`, `admin-ui/package.json`, or
`admin-ui/package-lock.json` changes in QW-3 — both findings resolve to
"verified, no in-range fix, keep + record rationale + followup", per the plan's
no-blind-bump rule.
