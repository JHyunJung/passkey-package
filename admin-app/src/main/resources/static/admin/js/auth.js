/* 로그인·MFA 단계 화면 스크립트(← pages/LoginPage.tsx, pages/MfaChallenge.tsx).
 * 로드 순서: htmx → ui.js → auth.js → alpine(마지막). 공통 틀의 app.js 는 싣지 않는다.
 * 비밀번호·인증 코드는 어디에도 저장하지 않는다. 로그인 실패 뒤 이메일만 sessionStorage 로 한 번 넘긴다(입력 유지). */
(function () {
  'use strict';

  const EMAIL_KEY = 'admin.login.email';
  const TOTP_RE = /^\d{6}$/;
  const RECOVERY_RE = /^[A-Z2-9]{4}-[A-Z2-9]{4}$/;

  function store(fn) { try { return fn(window.sessionStorage); } catch (e) { return null; } }

  function readCookie(name) {
    const parts = document.cookie ? document.cookie.split('; ') : [];
    for (const p of parts) {
      const i = p.indexOf('=');
      if (i > 0 && p.slice(0, i) === name) {
        try { return decodeURIComponent(p.slice(i + 1)); } catch (e) { return p.slice(i + 1); }
      }
    }
    return null;
  }

  /** lib/recoveryCode.ts formatRecoveryCode — 백엔드 알파벳(I·O·0·1 제외) 대문자 "XXXX-XXXX". */
  function formatRecoveryCode(input) {
    const ascii = String(input || '').replace(/[^A-Za-z0-9]/g, '');
    const cleaned = ascii.toUpperCase().replace(/[^ABCDEFGHJKLMNPQRSTUVWXYZ2-9]/g, '').slice(0, 8);
    return cleaned.length > 4 ? cleaned.slice(0, 4) + '-' + cleaned.slice(4) : cleaned;
  }
  window.adminFormatRecoveryCode = formatRecoveryCode;

  if (window.htmx) {
    // 422 는 폼 오류 조각이라 스왑한다(successful=false). 그 밖의 4xx/5xx 는 스왑하지 않는다.
    htmx.config.responseHandling = [
      { code: '204', swap: false },
      { code: '422', swap: true, error: true },
      { code: '[23]..', swap: true },
      { code: '[45]..', swap: false, error: true }
    ];
    htmx.config.historyCacheSize = 0;
  }

  // 폼의 _csrf 와 함께, 쿠키가 바뀌었을 때를 위해 요청마다 X-XSRF-TOKEN 도 붙인다(app.js 와 같은 규칙).
  document.addEventListener('htmx:configRequest', function (e) {
    const token = readCookie('XSRF-TOKEN');
    if (token) e.detail.headers['X-XSRF-TOKEN'] = token;
  });

  // HX-Redirect 와 함께 온 토스트(복구 코드 사용 경고)를 다음 화면으로 넘기는 일은 ui.js 가 한다(공통 틀 화면과 같은 규칙).
  function redirecting(xhr) { return !!(xhr && xhr.getResponseHeader && xhr.getResponseHeader('HX-Redirect')); }

  document.addEventListener('alpine:init', function () {

    // ── 로그인 폼 ──────────────────────────────────────────
    Alpine.data('loginForm', function () {
      return {
        error: null,        // 'credentials' | 'transient' | null
        status: null,       // 일시 오류의 HTTP 상태(네트워크 오류면 null)
        submitting: false,
        showPw: false,
        demoOn: false,
        init() {
          this.error = this.$root.getAttribute('data-error');
          const saved = store(function (s) { const v = s.getItem(EMAIL_KEY); s.removeItem(EMAIL_KEY); return v; });
          // 실패해서 다시 그려진 화면이면 이메일은 두고(비밀번호는 비어 있음) 비밀번호 칸에 포커스(autofocus).
          if (this.error === 'credentials' && saved) this.$refs.email.value = saved;
        },
        clearError() { this.error = null; },
        selectDemo(el) {
          this.demoOn = true;
          this.error = null;
          this.$refs.email.value = el.getAttribute('data-demo-email') || '';
          this.$refs.password.value = el.getAttribute('data-demo-password') || '';
        },
        onBefore(e) {
          if (e.detail.elt !== this.$root) return;
          if (this.submitting) { e.preventDefault(); return; }
          this.error = null;
          this.status = null;
          this.submitting = true;
          const email = this.$refs.email.value;
          store(function (s) { s.setItem(EMAIL_KEY, email); });
        },
        onAfter(e) {
          if (e.detail.elt !== this.$root) return;
          const xhr = e.detail.xhr;
          if (redirecting(xhr)) {
            // 성공(/admin, MFA 단계) 또는 실패(/admin/login?error) 이동 중 — 그대로 둔다. 성공이면 임시 이메일을 지운다.
            if (xhr.getResponseHeader('HX-Redirect') !== '/admin/login?error') store(function (s) { s.removeItem(EMAIL_KEY); });
            return;
          }
          this.submitting = false;
          const st = xhr ? xhr.status : 0;
          if (st >= 500) { this.error = 'transient'; this.status = st; return; }
          if (st >= 400) {
            // 401·403(예: CSRF 거부) 등: TSX 와 같이 자격 오류. 실패 화면으로 다시 그린다(이메일 유지·비밀번호 비움·포커스).
            window.location.assign('/admin/login?error');
          }
        },
        onNetworkError() { this.submitting = false; this.error = 'transient'; this.status = null; },
        retry() { if (!this.submitting) this.$root.requestSubmit(); },
      };
    });

    // ── 비밀번호 찾기·재설정, 가입 요청 공통 ─────────────────
    // 요청 중 입력 읽기 전용·중복 제출 차단, 5xx·네트워크 오류는 입력을 둔 채 "보내지 못했습니다"(H9).
    // 4xx(422 폼 오류·거부 카드)는 서버가 그린 카드로 스왑되므로 여기서 다루지 않는다.
    function submitState() {
      return {
        submitting: false,
        failure: false,      // 5xx·네트워크 — 입력을 둔 채 다시 시도
        rejected: false,     // 예상 밖 4xx(CSRF 403 등, 422 제외) — 새로 고침 안내
        status: null,
        onBefore(e) {
          if (e.detail.elt !== this.$root) return;
          if (this.submitting) { e.preventDefault(); return; }
          this.failure = false;
          this.rejected = false;
          this.status = null;
          this.submitting = true;
        },
        onAfter(e) {
          if (e.detail.elt !== this.$root) return;
          this.submitting = false;
          const st = e.detail.xhr ? e.detail.xhr.status : 0;
          if (st >= 500) { this.failure = true; this.status = st; this.focusSubmit(); }
          else if (st >= 400 && st !== 422) { this.rejected = true; this.status = st; }
        },
        onNetworkError() { this.submitting = false; this.failure = true; this.status = null; this.focusSubmit(); },
        focusSubmit() { this.$nextTick(() => { const b = this.$refs.submit; if (b) b.focus(); }); },
      };
    }
    function withInitial(el, key) { return el.getAttribute('data-' + key) || ''; }

    Alpine.data('forgotForm', function () {
      return Object.assign(submitState(), {
        email: '',
        init() { this.email = withInitial(this.$root, 'email'); },
        canSubmit() { return !this.submitting && this.email.trim().length > 0; },
      });
    });

    // lengthMeter: 6자당 1칸, 12자 이상이면 ok 색(문구로도 알림).
    Alpine.data('resetForm', function () {
      return Object.assign(submitState(), {
        pw: '',
        pw2: '',
        min: 12,
        mismatch() { return this.pw.length > 0 && this.pw2.length > 0 && this.pw !== this.pw2; },
        tooShort() { return this.pw.length > 0 && this.pw.length < this.min; },
        filled() { return Math.min(3, Math.floor(this.pw.length / 6)); },
        segColor(i) {
          if (i >= this.filled()) return 'var(--line)';
          return this.pw.length >= this.min ? 'var(--ok)' : 'var(--warn-dot)';
        },
        canSubmit() { return this.pw.length >= this.min && this.pw === this.pw2 && !this.submitting; },
      });
    });

    Alpine.data('signupForm', function () {
      const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      return Object.assign(submitState(), {
        email: '',
        emailTouched: false,
        pw: '',
        pw2: '',
        reason: '',
        role: 'RP_ADMIN',
        init() {
          this.email = withInitial(this.$root, 'email');
          this.reason = withInitial(this.$root, 'reason');
          const r = withInitial(this.$root, 'role');
          if (r === 'PLATFORM_OPERATOR') this.role = r;
        },
        emailInvalid() { const v = this.email.trim(); return this.emailTouched && v.length > 0 && !EMAIL_RE.test(v); },
        mismatch() { return this.pw.length > 0 && this.pw2.length > 0 && this.pw !== this.pw2; },
        pwTooShort() { return this.pw.length > 0 && this.pw.length < 12; },
        canSubmit() {
          return EMAIL_RE.test(this.email.trim()) && this.pw.length >= 12 && this.pw.length <= 128
            && this.pw === this.pw2 && this.reason.length <= 500 && !this.submitting;
        },
      });
    });

    // ── MFA 단계 ───────────────────────────────────────────
    Alpine.data('mfaChallenge', function () {
      return {
        mode: 'totp',
        code: '',           // 복구 코드 입력값
        submitting: false,
        failed: false,      // 코드 틀림(422) 뒤 — TOTP 시계 안내
        transient: null,    // 일시 오류 제목(describeError title)
        otpTick: 0,         // OTP 칸 이벤트마다 올려 ready 를 다시 계산하게 하는 보조 값
        init() { this.$nextTick(() => this.focusInput()); },
        otpData() {
          const el = this.$refs.otp && this.$refs.otp.querySelector('.otp-set');
          return el ? Alpine.$data(el) : null;
        },
        otpValue() { void this.otpTick; const d = this.otpData(); return d ? d.value : ''; },
        // otpInput 의 value 는 반응형이지만, 칸 이벤트(input·paste·keydown·완성) 뒤에도 한 번 더 다시 계산하게 한다.
        syncOtp() { this.$nextTick(() => { this.otpTick++; }); },
        get ready() { return this.mode === 'totp' ? TOTP_RE.test(this.otpValue()) : RECOVERY_RE.test(this.code); },
        get wrongTotp() { return this.mode === 'totp' && this.failed && !this.transient; },
        focusInput() {
          if (this.mode === 'totp') { const c = this.$refs.otp && this.$refs.otp.querySelector('.otp__cell'); if (c) c.focus(); }
          else if (this.$refs.recovery) this.$refs.recovery.focus();
        },
        setCellsDisabled(v) {
          if (!this.$refs.otp) return;
          this.$refs.otp.querySelectorAll('.otp__cell').forEach(function (c) { c.disabled = v; });
        },
        clearError() { this.failed = false; this.transient = null; if (this.$refs.error) this.$refs.error.innerHTML = ''; },
        switchMode(next) {
          this.mode = next;
          this.code = '';
          this.clearError();
          this.$nextTick(() => this.focusInput());
        },
        onRecoveryInput(e) {
          this.code = formatRecoveryCode(e.target.value);
          e.target.value = this.code;
        },
        onBefore(e) {
          if (e.detail.elt !== this.$root.querySelector('#mfa-form')) return;
          // 자동 제출과 수동 확인이 겹쳐도, 형식이 맞지 않아도 보내지 않는다.
          if (this.submitting || !this.ready) { e.preventDefault(); return; }
          this.clearError();
          this.submitting = true;
          this.setCellsDisabled(true);
        },
        onAfter(e) {
          if (e.detail.elt !== this.$root.querySelector('#mfa-form')) return;
          const xhr = e.detail.xhr;
          if (redirecting(xhr)) return; // 확인 성공(/admin) 또는 잠금(/admin/login) 이동 중
          this.finish();
          const st = xhr ? xhr.status : 0;
          if (st === 422) { this.failed = true; this.resetInput(); return; }
          if (st >= 500) { this.transient = '서버가 잠시 응답하지 않습니다'; this.resetInput(); return; }
          if (st >= 400) { this.failed = true; this.resetInput(); }
        },
        onNetworkError(timeout) {
          this.finish();
          this.transient = timeout ? '서버가 잠시 응답하지 않습니다' : '네트워크에 연결할 수 없습니다';
          this.resetInput();
        },
        finish() { this.submitting = false; this.setCellsDisabled(false); },
        // 실패하면 칸을 비우고 첫 칸(복구 코드 칸)으로 포커스 — 클릭 없이 바로 다시 입력.
        resetInput() {
          this.$nextTick(() => {
            if (this.mode === 'totp') { const d = this.otpData(); if (d) d.clear(); else this.focusInput(); this.syncOtp(); }
            else { this.code = ''; if (this.$refs.recovery) { this.$refs.recovery.value = ''; this.$refs.recovery.focus(); } }
          });
        },
      };
    });
  });
})();
