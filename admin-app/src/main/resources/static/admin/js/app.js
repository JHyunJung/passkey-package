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
      a.hidden = location.pathname === a.getAttribute('data-hide-on-path');
    });
  }
  document.addEventListener('htmx:afterSettle', syncHiddenLinks);
  window.addEventListener('popstate', syncHiddenLinks);

  // ── Alpine 컴포넌트 ───────────────────────────────────
  document.addEventListener('alpine:init', function () {
    // 사이드바 "위변조 N" 배지가 읽는 값. chainCard 가 응답마다 갱신하고, 이동으로 사이드바가 다시 그려져도 남는다.
    Alpine.store('chain', { tampered: 0 });

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
