/* 공통 틀 스크립트 — htmx 설정과 공통 틀(사이드바·상단 바)의 Alpine 컴포넌트.
 * 로드 순서: htmx → alpine-focus → ui.js → app.js → alpine(마지막). htmx 는 이미 있고, Alpine 컴포넌트는 alpine:init 에 등록한다.
 * 토스트는 ui.js 의 toastHost 하나만 쓴다(HX-Trigger 의 `toast` 이벤트를 그 컴포넌트가 window 에서 받는다). */
(function () {
  'use strict';

  // ── CSRF ───────────────────────────────────────────────
  // 레이아웃의 hx-headers 는 렌더 시점 토큰이다. 로그인 직후처럼 쿠키가 바뀌어도 맞도록 요청마다 쿠키(XSRF-TOKEN)를 다시 읽는다.
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
  document.addEventListener('htmx:configRequest', function (e) {
    const token = readCookie('XSRF-TOKEN');
    if (token) e.detail.headers['X-XSRF-TOKEN'] = token;
  });

  // ── htmx 설정 ─────────────────────────────────────────
  if (window.htmx) {
    // 응답 처리: 4xx/5xx 도 htmx 가 HX-Trigger 를 처리한다(토스트). 본문은 422 만 스왑한다(폼 검증 오류 조각).
    // 422 는 스왑하되 error:true 라 afterRequest 의 successful 은 false 다 — 저장 바가 "저장됨"으로 오해하지 않는다.
    // 204 는 스왑하지 않는다. 그 밖의 오류 응답은 본문 없이 토스트만 남는다(서버 규칙 A).
    htmx.config.responseHandling = [
      { code: '204', swap: false },
      { code: '422', swap: true, error: true },
      { code: '[23]..', swap: true },
      { code: '[45]..', swap: false, error: true }
    ];
    // 이동 기록에 화면 내용을 localStorage 로 남기지 않는다(운영자 이메일·tenant 정보). 뒤로 가기는 서버에서 다시 받는다.
    htmx.config.historyCacheSize = 0;
  }

  // ── 네트워크 오류·시간 초과 토스트 ─────────────────────
  // 서버 오류 응답의 토스트는 서버가 HX-Trigger 로 보낸다. 서버에 닿지 못한 경우만 여기서 같은 토스트 컴포넌트에 넣는다.
  // data-quiet 요소가 보낸 요청(주변 UI: 폴링 배너·체인 카드·스위처 목록·다시 시도)의 실패는 알리지 않는다.
  function networkToast(e, title, message) {
    const el = e.detail && e.detail.elt;
    // 요청을 보낸 요소 자신이 data-quiet 일 때만 조용히(폴링 자리·다시 시도). 그 안의 링크·전환 버튼은 사용자 동작이라 알린다.
    if (el && el.hasAttribute && el.hasAttribute('data-quiet')) return;
    window.dispatchEvent(new CustomEvent('toast', { detail: { kind: 'error', title: title, message: message } }));
  }
  document.addEventListener('htmx:sendError', function (e) {
    networkToast(e, '네트워크에 연결할 수 없습니다', '연결 상태를 확인한 뒤 다시 시도하세요.');
  });
  document.addEventListener('htmx:timeout', function (e) {
    networkToast(e, '서버가 잠시 응답하지 않습니다', '응답이 오지 않아 요청을 중단했습니다. 잠시 후 다시 시도하세요.');
  });

  // ── 라이선스 배너 링크: 이미 /license 화면이면 숨긴다 ──
  // 배너는 hx-preserve 로 이동 사이에 유지되므로 서버가 아니라 현재 주소로 판단한다(LicenseBanner.tsx: pathname !== '/license').
  function syncHiddenLinks() {
    document.querySelectorAll('[data-hide-on-path]').forEach(function (a) {
      const hide = a.getAttribute('data-hide-on-path');
      const p = location.pathname.replace(/\/+$/, '');
      a.hidden = p === hide;
    });
  }
  document.addEventListener('htmx:afterSettle', syncHiddenLinks);
  window.addEventListener('popstate', syncHiddenLinks);

  // ── 프로그램 이동 ─────────────────────────────────────
  // 숨은 boost 링크(#admin-nav-link, 팔레트 조각)를 눌러 이동한다: hx-boost 와 이동 확인(unsavedGuard)이 링크 클릭과 같은 길을 탄다.
  // 링크가 없으면(로그인 전 화면 등) 일반 이동.
  window.adminNavigate = function (url) {
    const a = document.getElementById('admin-nav-link');
    if (!a) { window.location.assign(url); return; }
    a.setAttribute('href', url);
    a.click();
  };

  // ── 저장 안 한 변경 경고 ───────────────────────────────
  // 새로고침·탭 닫기: 리스너 하나. dirty 는 Alpine.store('dirty') 하나만 본다(저장 중·통과 허가·해제 상태는 스토어가 안다).
  window.addEventListener('beforeunload', function (e) {
    if (!window.Alpine) return;
    if (Alpine.store('dirty').needsUnloadPrompt()) { e.preventDefault(); e.returnValue = ''; }
  });
  // hx-boost 이동(GET): dirty 면 요청을 취소하고 이동 확인(unsavedGuard)을 띄운다. 주소가 현재 화면(경로+쿼리)이면 건너뛴다(useUnsavedGuard 의 blocker 조건).
  document.addEventListener('htmx:beforeRequest', function (e) {
    const d = e.detail || {};
    const cfg = d.requestConfig || {};
    if (!window.Alpine || !(d.boosted || cfg.boosted)) return;
    if (String(cfg.verb || '').toLowerCase() !== 'get') return;
    if (!Alpine.store('dirty').needsGuard()) return;
    const pi = d.pathInfo || {};
    const u = new URL(pi.finalRequestPath || pi.requestPath || '', window.location.href);
    if (u.pathname === window.location.pathname && u.search === window.location.search) return;
    e.preventDefault();
    window.dispatchEvent(new CustomEvent('unsaved-ask', { detail: { href: u.href } }));
  });
  // 저장 성공(서버 HX-Trigger: saved): 수동 표시(mark)를 지운다. saveBar 폼은 자기 요청 결과로 기준선을 갱신해 스스로 센다.
  document.addEventListener('saved', function () { if (window.Alpine) Alpine.store('dirty').clear(); });
  // 이동이 끝나 새 화면이 들어오면(boost 이동·뒤로 가기) 이전 화면의 dirty 표시를 버린다.
  document.addEventListener('htmx:afterSwap', function (e) { if (e.detail && e.detail.boosted && window.Alpine) Alpine.store('dirty').reset(); });
  document.addEventListener('htmx:historyRestore', function () { if (window.Alpine) Alpine.store('dirty').reset(); });

  // ── 전역 단축키 (useGoShortcuts / usePaletteShortcut / useHelpShortcut) ──
  // 리스너는 window 에 한 번만 단다(이동으로 body 가 바뀌어도 유지). 팔레트·시트는 window 이벤트로 연다.
  function isTyping(t) {
    const el = t;
    if (!el || !el.tagName) return false;
    return /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || !!el.isContentEditable
      || !!(el.closest && el.closest('[contenteditable]:not([contenteditable="false"])'));
  }
  window.addEventListener('keydown', function (e) {
    // ⌘K / Ctrl+K 토글. 비밀값·확인 alertdialog 가 열려 있으면 무시(팔레트 이동으로 비밀값이 사라질 수 있다).
    if (!(e.metaKey || e.ctrlKey) || String(e.key).toLowerCase() !== 'k') return;
    e.preventDefault();
    if (document.querySelector('[role="alertdialog"][aria-modal="true"]')) return;
    window.dispatchEvent(new CustomEvent('palette-toggle'));
  });
  window.addEventListener('keydown', function (e) {
    // ? → 단축키 시트. 입력 중·모달 열림이면 무시.
    if (e.key !== '?' || e.metaKey || e.ctrlKey || e.altKey || e.repeat) return;
    if (isTyping(e.target)) return;
    if (document.querySelector('[aria-modal="true"]')) return;
    e.preventDefault();
    window.dispatchEvent(new CustomEvent('open-shortcuts'));
  });
  const GO_PATHS = { t: '/admin/tenants', a: '/admin/activity', c: '/admin/audit-chain', s: '/admin/settings' };
  let goArmed = false;
  let goTimer = null;
  window.addEventListener('keydown', function (e) {
    // G → T/A/C/S 이동(PLATFORM 에게만: 시트 조각의 data-go-shortcuts). 1초 안에 다음 키가 없으면 풀린다.
    if (!document.querySelector('[data-go-shortcuts="true"]')) { goArmed = false; return; }
    if (e.repeat) return;
    if (e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target) || document.querySelector('[aria-modal="true"]')) { goArmed = false; return; }
    const k = String(e.key).toLowerCase();
    if (goArmed && Object.prototype.hasOwnProperty.call(GO_PATHS, k)) {
      e.preventDefault();
      goArmed = false;
      clearTimeout(goTimer);
      window.adminNavigate(GO_PATHS[k]);
      return;
    }
    if (k === 'g') { goArmed = true; clearTimeout(goTimer); goTimer = setTimeout(function () { goArmed = false; }, 1000); return; }
    goArmed = false;
  });


  // ── 로그아웃·세션 연장 ─────────────────────────────────
  // 로그아웃은 Spring Security 의 POST /admin/logout(CSRF 포함)이다. 숨은 폼(유휴 모달 조각)을 제출하고, 곧 떠나므로 저장 안 한 변경 경고는 끈다
  // (React 의 handleLogout 도 확인 없이 즉시 로그아웃한다). 폼이 없으면(드문 경우) 로그인 화면으로 이동.
  function logoutNow() {
    if (window.Alpine) Alpine.store('dirty').disarm();
    const f = document.getElementById('idle-logout-form');
    if (f) f.submit();
    else window.location.assign('/admin/login');
  }
  // 세션 연장 = GET /admin/api/me(기존 엔드포인트, getMe()). 실패는 throw. 로그인 화면으로 리다이렉트된 응답은 실패로 본다.
  async function extendSession() {
    const res = await fetch('/admin/api/me', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (res.redirected || !res.ok) {
      let msg = '';
      try { const j = await res.json(); msg = (j && j.message) || ''; } catch (e) { /* 본문 없음 */ }
      const err = new Error(msg || ('HTTP ' + res.status));
      err.httpStatus = res.redirected ? 401 : res.status;
      err.serverMessage = msg;
      throw err;
    }
  }
  // describeError.ts 와 같은 문구.
  function describeFetchError(e) {
    const st = e && e.httpStatus;
    if (st >= 500) return { title: '서버가 잠시 응답하지 않습니다', detail: '저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.' };
    if (st === 403) return { title: '권한이 없습니다', detail: '이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.' };
    if (st === 429) return { title: '요청이 너무 많습니다', detail: '잠시 후 다시 시도하세요.' };
    if (st === 404) return { title: '찾을 수 없습니다', detail: '삭제되었거나 주소가 바뀌었을 수 있습니다.' };
    if (st) return { title: '요청을 처리하지 못했습니다', detail: e.serverMessage || '' };
    if (e instanceof TypeError) return { title: '네트워크에 연결할 수 없습니다', detail: '연결 상태를 확인한 뒤 다시 시도하세요.' };
    return { title: '알 수 없는 오류가 발생했습니다', detail: e instanceof Error ? e.message : String(e) };
  }

  // ── Alpine 컴포넌트 ───────────────────────────────────
  document.addEventListener('alpine:init', function () {
    // 사이드바 "위변조 N" 배지가 읽는 값. chainCard 가 응답마다 갱신하고, 이동으로 사이드바가 다시 그려져도 남는다.
    Alpine.store('chain', { tampered: 0 });

    // 저장 안 한 변경의 단일 출처(R10). 화면 폼은 x-on:input="$store.dirty.mark()" 로 알리고, 저장 성공 응답의 HX-Trigger: saved 가 clear() 한다.
    // saveBar 는 자기 폼의 변경 수·저장 중 여부를 report() 로 보고한다. count 는 둘의 합(mark 는 1건으로 센다).
    Alpine.store('dirty', {
      count: 0,
      manual: false,
      parts: {},
      disarmed: false,
      passUntil: 0,
      mark() { this.manual = true; this.refresh(); },
      clear() { this.manual = false; this.refresh(); },
      report(id, n, saving) {
        if (n > 0 || saving) this.parts[id] = { n: n, saving: !!saving };
        else delete this.parts[id];
        this.refresh();
      },
      refresh() {
        let n = this.manual ? 1 : 0;
        Object.keys(this.parts).forEach((k) => { n += this.parts[k].n; });
        this.count = n;
      },
      saving() { return Object.keys(this.parts).some((k) => this.parts[k].saving); },
      // 비 htmx 제출·로그아웃처럼 곧 페이지를 떠나는 동작: 경고를 끈다.
      disarm() { this.disarmed = true; },
      // 이동 확인에서 "이동"을 누른 직후 5초 동안 경고 없이 통과(이동 한 번).
      allow() { this.passUntil = Date.now() + 5000; },
      passing() { return Date.now() < this.passUntil; },
      reset() { this.manual = false; this.parts = {}; this.disarmed = false; this.passUntil = 0; this.refresh(); },
      needsGuard() { return this.count > 0 && !this.disarmed && !this.passing(); },
      needsUnloadPrompt() { return this.needsGuard() && !this.saving(); },
    });

    // 명령 팔레트(CommandPalette.tsx). 결과는 서버 조각(GET /admin/_/palette)이고, 이 컴포넌트는 열기·선택·키보드·실행을 맡는다.
    Alpine.data('commandPalette', function () {
      return {
        open: false,
        loading: false,
        sel: 0,
        count: 0,
        options() { return Array.from(this.$refs.list.querySelectorAll('[role="option"]')); },
        show() { if (!this.open) { this.reset(); this.open = true; } },
        toggle() { if (this.open) this.close(); else this.show(); },
        close() { this.open = false; },
        // 열 때마다: 검색칸 비움, 이전 결과 제거(이전 tenant 가 남지 않게), 결과 새로 요청.
        reload() {
          this.$refs.list.innerHTML = '';
          this.count = 0;
          this.sel = 0;
          this.loading = true;
          this.$nextTick(() => window.htmx && htmx.trigger(this.$refs.input, 'palette-load'));
        },
        reset() { this.$refs.input.value = ''; this.reload(); },
        // 결과가 바뀌면(검색어·다시 확인) 첫 실행 가능한 항목을 선택한다.
        afterSwap(e) {
          if (e.target !== this.$refs.list) return;
          this.loading = false;
          this.count = this.options().length;
          this.sel = this.firstEnabled();
          this.apply();
        },
        firstEnabled() {
          const i = this.options().findIndex((o) => !o.disabled);
          return i < 0 ? 0 : i;
        },
        // dir 방향으로 다음 실행 가능한 항목. 없으면 제자리 — 비활성 항목에 선택이 멈추지 않는다.
        step(dir) {
          const os = this.options();
          for (let i = this.sel + dir; i >= 0 && i < os.length; i += dir) {
            if (!os[i].disabled) { this.sel = i; break; }
          }
          this.apply();
        },
        apply() {
          const os = this.options();
          os.forEach((o, i) => o.setAttribute('aria-selected', String(i === this.sel)));
          const cur = os[this.sel];
          if (cur) {
            this.$refs.input.setAttribute('aria-activedescendant', cur.id);
            if (cur.scrollIntoView) cur.scrollIntoView({ block: 'nearest' });
          } else this.$refs.input.removeAttribute('aria-activedescendant');
        },
        onKey(e) {
          if (!this.open) return;
          if (e.key === 'Escape') { this.close(); return; }
          if (e.key === 'ArrowDown') { e.preventDefault(); this.step(1); }
          if (e.key === 'ArrowUp') { e.preventDefault(); this.step(-1); }
          if (e.key === 'Enter') {
            // 항목 버튼에 포커스가 있으면 그 버튼의 기본 동작(클릭)에 맡긴다 — 선택 인덱스의 다른 명령을 실행하지 않는다.
            if (e.target && e.target.closest && e.target.closest('button')) return;
            e.preventDefault();
            const o = this.options()[this.sel];
            if (o && !o.disabled) this.run(o);
          }
        },
        optionOf(e) { return e.target && e.target.closest ? e.target.closest('[role="option"]') : null; },
        hover(e) {
          const o = this.optionOf(e);
          if (o && !o.disabled) { this.sel = this.options().indexOf(o); this.apply(); }
        },
        activate(e) {
          const o = this.optionOf(e);
          if (o && !o.disabled) this.run(o);
        },
        // 실행: 이동·동작을 하고 닫는다(keepOpen 은 닫지 않는다). 닫힘의 포커스 복귀가 끝난 뒤 실행해 이어서 열리는 창이 제자리를 잡게 한다.
        run(o) {
          const action = o.dataset.action;
          const href = o.dataset.href;
          if (action === 'recheck') { this.reload(); return; }
          this.close();
          setTimeout(() => {
            if (action === 'logout') logoutNow();
            else if (action === 'shortcuts') window.dispatchEvent(new CustomEvent('open-shortcuts'));
            else if (href) window.adminNavigate(href);
          }, 0);
        },
      };
    });

    // 유휴 세션 모달(IdleSessionModal.tsx). 경고 시점·카운트다운 길이는 전체 유휴 시간에서 파생한다
    // (짧은 정책에서도 모달 표시 시점 + 카운트다운 = 전체 유휴 시간). 연장 요청 중 만료되면 10초까지만 기다린다.
    Alpine.data('idleSession', function () {
      const WARN_LEAD_SECONDS = 60;
      const EXTEND_GRACE_MS = 10000;
      const ACTIVITY = ['mousemove', 'keydown', 'click', 'scroll'];
      return {
        minutes: 30,
        total: 1800,
        warnAfter: 1740,
        countdown: 60,
        open: false,
        secondsLeft: 60,
        extending: false,
        extendError: null,
        expired: false,
        loggedOut: false,
        gone: false,
        idleTimer: null,
        tickTimer: null,
        graceTimer: null,
        onActivity: null,
        onRequest: null,
        ring: 2 * Math.PI * 28,
        init() {
          const m = Number(this.$root.dataset.minutes);
          this.minutes = m > 0 ? m : 30;
          this.total = this.minutes * 60;
          this.warnAfter = Math.max(this.total - WARN_LEAD_SECONDS, 10);
          this.countdown = Math.max(this.total - this.warnAfter, 1);
          this.secondsLeft = this.countdown;
          // 사용자 활동(마우스·키·클릭·스크롤)과 사용자 동작이 만든 htmx 요청이 타이머를 리셋한다. 모달이 열려 있는 동안·로그아웃 뒤에는 무시.
          this.onActivity = () => this.bump();
          ACTIVITY.forEach((n) => window.addEventListener(n, this.onActivity, { passive: true }));
          this.onRequest = (e) => {
            const el = e.detail && e.detail.elt;
            if (el && el.hasAttribute && el.hasAttribute('data-quiet')) return; // 폴링(배너·체인 카드)은 사용자 활동이 아니다
            this.bump();
          };
          document.addEventListener('htmx:beforeRequest', this.onRequest);
          this.bump();
        },
        destroy() {
          this.gone = true;
          clearTimeout(this.idleTimer);
          clearInterval(this.tickTimer);
          clearTimeout(this.graceTimer);
          ACTIVITY.forEach((n) => window.removeEventListener(n, this.onActivity));
          document.removeEventListener('htmx:beforeRequest', this.onRequest);
        },
        // 모달이 이미 열려 있는 동안에는 다시 무장하지 않는다(카운트다운이 리셋되지 않게). 로그아웃한 뒤에도 경고를 다시 띄우지 않는다.
        bump() {
          if (this.open || this.loggedOut || this.gone) return;
          clearTimeout(this.idleTimer);
          this.idleTimer = setTimeout(() => this.warn(), this.warnAfter * 1000);
        },
        warn() {
          this.secondsLeft = this.countdown;
          this.extendError = null;
          this.expired = false;
          this.open = true;
          clearInterval(this.tickTimer);
          this.tickTimer = setInterval(() => this.tick(), 1000);
        },
        tick() {
          if (!this.open || this.secondsLeft <= 0) return;
          this.secondsLeft = this.secondsLeft <= 1 ? 0 : this.secondsLeft - 1;
          if (this.secondsLeft !== 0) return;
          // 카운트다운 0: 연장 요청이 진행 중이면 결과를 기다리되 유예 시간이 지나면 로그아웃.
          this.expired = true;
          if (this.extending) {
            if (!this.graceTimer) this.graceTimer = setTimeout(() => this.expireLogout(), EXTEND_GRACE_MS);
            return;
          }
          this.expireLogout();
        },
        expireLogout() {
          if (this.loggedOut) return;
          this.loggedOut = true;
          clearTimeout(this.graceTimer);
          this.graceTimer = null;
          clearTimeout(this.idleTimer);
          clearInterval(this.tickTimer);
          this.open = false;
          logoutNow();
        },
        logoutNow() { this.expireLogout(); },
        closeModal() {
          clearTimeout(this.graceTimer);
          this.graceTimer = null;
          clearInterval(this.tickTimer);
          this.open = false;
          this.bump();
        },
        // 연장: 세션 갱신(GET /admin/api/me). 실패는 모달 안에 오류로 보이고 열린 채 유지된다. 요청 도중 이미 만료됐다면 실패는 로그아웃으로 이어진다.
        async extend() {
          if (this.extending) return; // 연타로 중복 호출 방지
          this.extending = true;
          this.extendError = null;
          try {
            await extendSession();
            if (!this.gone && !this.loggedOut) this.closeModal();
          } catch (err) {
            if (this.gone || this.loggedOut) return;
            if (this.expired) { this.expireLogout(); return; }
            this.extendError = describeFetchError(err);
          } finally {
            if (!this.gone) {
              this.extending = false;
              // 비활성이던 버튼이 다시 켜진 뒤에 포커스를 되돌린다(비활성 버튼은 포커스를 받지 못함).
              if (this.extendError) this.$nextTick(() => { const b = this.$refs.extend; if (b) b.focus(); });
            }
          }
        },
        get pct() { return this.secondsLeft / this.countdown; },
        get ringOffset() { return this.ring * (1 - this.pct); },
        get tone() { return this.secondsLeft > 20 ? 'var(--warn-dot)' : 'var(--bad)'; },
        // 스크린리더에는 매초가 아니라 30초·10초 남았을 때만 알린다.
        get announcement() {
          if (this.secondsLeft <= 10) return '세션 만료까지 10초 남았습니다.';
          if (this.secondsLeft <= 30) return '세션 만료까지 30초 남았습니다.';
          return '';
        },
      };
    });

    // 단축키 표기(lib/platform.ts): Mac·iOS 는 ⌘K, 그 밖은 Ctrl K. 서버 렌더는 Ctrl K.
    Alpine.data('platformKey', function () {
      const nav = typeof navigator === 'undefined' ? null : navigator;
      const mac = !!nav && /Mac|iPhone|iPad|iPod/.test(nav.platform || nav.userAgent || '');
      return { key: mac ? '⌘K' : 'Ctrl K' };
    });

    // 상단 바 드롭다운(RP 전환·도움말·사용자). 바깥 mousedown·Esc 로 닫힌다.
    // nav=true(도움말): 열면 첫 항목에 포커스, ↑/↓ 로 항목 이동, 닫으면 포커스를 트리거로 돌린다.
    Alpine.data('shellMenu', function (nav) {
      return {
        open: false,
        switching: false,
        trigger() { return this.$root.querySelector('[aria-haspopup]'); },
        items() { return Array.from(this.$root.querySelectorAll('[role="menuitem"], [role="menuitemradio"]')); },
        toggle() {
          this.open = !this.open;
          if (this.open && nav) this.$nextTick(() => { const f = this.items()[0]; if (f) f.focus(); });
        },
        close() {
          if (!this.open) return;
          // 항목이 사라지기 전에 포커스를 돌려야 이후 열리는 시트도 트리거로 복귀한다.
          if (nav) { const t = this.trigger(); if (t) t.focus(); }
          this.open = false;
        },
        outside(e) { if (this.open && !this.$root.contains(e.target)) this.open = false; },
        arrow(e) {
          if (!nav || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) return;
          const els = this.items();
          if (els.length === 0) return;
          e.preventDefault();
          const i = els.indexOf(document.activeElement);
          const next = e.key === 'ArrowDown' ? (i + 1) % els.length : (i <= 0 ? els.length - 1 : i - 1);
          els[next].focus();
        },
      };
    });

    // 사이드바 감사 체인 카드 상태(Sidebar.tsx). 성공 응답은 서버가 그린 조각(#chain-card-body)이 보이고,
    // 첫 응답 전이거나 "다시 시도" 직후엔 "검증 중… N초째 확인 중", 실패(비 2xx·네트워크·시간 초과)는 "검증 상태 확인 불가".
    // 경과 초는 검증 중일 때만 1초마다 갱신한다.
    Alpine.data('chainCard', function () {
      const hm = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
      return {
        checking: true,
        failed: false,
        inflight: 0,
        lastOk: null,
        checkStart: Date.now(),
        now: Date.now(),
        timer: null,
        init() {
          this.timer = setInterval(() => { if (this.checking) this.now = Date.now(); }, 1000);
        },
        destroy() { clearInterval(this.timer); },
        get secs() { return Math.max(0, Math.floor((this.now - this.checkStart) / 1000)); },
        get lastText() {
          return this.lastOk ? '서버 응답이 없습니다 · ' + hm.format(this.lastOk) + ' 마지막 확인' : '서버 응답이 없습니다';
        },
        start() { const t = Date.now(); this.checking = true; this.checkStart = t; this.now = t; },
        // 이 카드의 조회 요청인지(카드 안 boost 링크 등 다른 요청의 이벤트는 무시).
        mine(e) {
          const pi = e.detail && e.detail.pathInfo;
          return !!pi && String(pi.requestPath || pi.finalRequestPath || '').indexOf('/admin/_/shell/chain-card') >= 0;
        },
        before(e) { if (this.mine(e)) this.inflight++; },
        done(e) {
          if (!this.mine(e)) return;
          this.inflight = Math.max(0, this.inflight - 1);
          if (e.detail.successful) {
            this.checking = false;
            this.failed = false;
            this.lastOk = new Date();
            const t = this.$root.querySelector('[data-chain-tampered]');
            Alpine.store('chain').tampered = t ? parseInt(t.getAttribute('data-tampered'), 10) || 0 : 0;
            return;
          }
          // hx-sync replace 로 중단된 앞 요청도 실패로 올라온다. 그 직후 새 요청이 시작되면 실패로 보지 않는다.
          setTimeout(() => {
            if (this.inflight > 0) return;
            this.checking = false;
            this.failed = true;
          }, 0);
        },
      };
    });
  });
})();
