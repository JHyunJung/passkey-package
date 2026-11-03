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
  // 새 링크를 만들어 htmx.process 한 뒤 눌러 이동한다: hx-boost 와 이동 확인(unsavedGuard)이 링크 클릭과 같은 길을 탄다.
  // htmx 2.0.4 는 boost 링크의 href 를 처리(process)하는 시점에 한 번 읽어 두고(boostElement: o=ee(t,"href")) 이후 setAttribute 는 보지 않으며
  // DOM 변이도 관찰하지 않는다. 그래서 링크를 재사용하지 않고 매번 새로 만들어 process 한다.
  // htmx 가 없으면(드문 경우) 일반 이동. 안전장치: 3초 뒤에도 통과 허가가 남아 있으면 지운다(요청이 시작되지 않은 경우).
  window.adminNavigate = function (url) {
    if (!window.htmx || !document.body) { window.location.assign(url); return; }
    const a = document.createElement('a');
    a.setAttribute('href', url);
    a.hidden = true;
    a.setAttribute('tabindex', '-1');
    a.setAttribute('aria-hidden', 'true');
    document.body.appendChild(a);
    htmx.process(a);
    a.click();
    setTimeout(function () { a.remove(); if (window.Alpine) Alpine.store('dirty').endPass(); }, 3000);
  };

  // ── 저장 안 한 변경 경고 ───────────────────────────────
  // 새로고침·탭 닫기: 리스너 하나. dirty 는 Alpine.store('dirty') 하나만 본다(저장 중·통과 허가·해제 상태는 스토어가 안다).
  window.addEventListener('beforeunload', function (e) {
    if (!window.Alpine) return;
    const d = Alpine.store('dirty');
    if (d.passing()) { d.endPass(); return; } // 이동 확인에서 "이동"을 누른 일반 이동 한 번
    if (Alpine.store('dirty').needsUnloadPrompt()) { e.preventDefault(); e.returnValue = ''; }
  });
  // hx-boost 이동(GET) + 본문 영역을 바꾸는 조각 GET(요소나 조상에 data-guard-dirty): dirty 면 요청을 취소하고 이동 확인(unsavedGuard)을 띄운다.
  // 탭 링크처럼 명시적 hx-get 인 요소는 boost 가 아니고 클릭 preventDefault 로는 htmx 가 멈추지 않으므로 이 표시로 같은 길을 태운다.
  // "이동"은 요소가 <a href> 면 그 href(화면 주소, boost 이동), 아니면 요청 주소로 새 boost 이동(adminNavigate)을 한다 — 조각을 직접 다시 보내지 않는다.
  // boost 이동은 주소가 현재 화면(경로+쿼리)이면 건너뛴다(useUnsavedGuard 의 blocker 조건).
  document.addEventListener('htmx:beforeRequest', function (e) {
    const d = e.detail || {};
    const cfg = d.requestConfig || {};
    if (!window.Alpine) return;
    const elt = d.elt || e.target;
    const marked = elt && elt.closest ? elt.closest('[data-guard-dirty]') : null;
    if (!(d.boosted || cfg.boosted || marked)) return;
    if (String(cfg.verb || '').toLowerCase() !== 'get') return;
    if (Alpine.store('dirty').passing()) { Alpine.store('dirty').endPass(); return; } // 확정된 이동 한 번만 통과(일회성)
    if (!Alpine.store('dirty').needsGuard()) return;
    const pi = d.pathInfo || {};
    const link = !d.boosted && !cfg.boosted && elt.closest ? elt.closest('a[href]') : null;
    const u = new URL(link ? link.getAttribute('href') : (pi.finalRequestPath || pi.requestPath || ''), window.location.href);
    // 같은 주소 건너뛰기는 boost 이동만. 표시된 조각 요청(활성 탭 다시 누르기 등)은 같은 주소여도 내용을 다시 받아 편집을 버리므로 똑같이 묻는다.
    if (!marked && u.pathname === window.location.pathname && u.search === window.location.search) return;
    e.preventDefault();
    window.dispatchEvent(new CustomEvent('unsaved-ask', { detail: { href: u.href } }));
  });
  document.addEventListener('saved', function () { if (window.Alpine) Alpine.store('dirty').clear(); });
  // 이동이 끝나 새 화면이 들어오면(boost 이동·뒤로 가기) 이전 화면의 dirty 표시를 버린다.
  document.addEventListener('htmx:afterSwap', function (e) { if (e.detail && e.detail.boosted && window.Alpine) Alpine.store('dirty').reset(); });
  document.addEventListener('htmx:historyRestore', function () { if (window.Alpine) Alpine.store('dirty').reset(); });
  // 뒤로/앞으로 가기(useBlocker 는 POP 도 막는다, 스택은 그대로 두고 POP 만 보류했다가 진행): htmx 는 window.onpopstate 로 화면을 교체한다.
  // 이력 항목마다 단조 증가 번호(idx)를 history.state 에 둔다. htmx 2.0.4 는 history.replaceState({htmx:true}) 를 여러 곳에서 부른다
  // (saveCurrentPageToHistory: 화면을 바꾸는 요청 직전 떠나는 항목에, HX-Location 에서, restoreHistory 시작에 도착 항목에 / replace-url) —
  // 이벤트에 기대어 다시 찍으면 놓치므로 history.pushState/replaceState 를 한 번 감싼다:
  //   replaceState(state): state 에 idx 가 없으면 현재 항목의 idx 를 병합(htmx 의 {htmx:true} 필드는 그대로 둔다).
  //   pushState(state): idx = 현재 idx + 1 을 부여(새 항목이 되고 앞으로 가기 기록은 브라우저가 자르므로 스택 안의 번호는 항상 연속이다 — delta 가 곧 history.go 폭).
  // 로드 시 현재 항목에 번호를 찍고(새로고침이면 history.state 에 남은 번호를 이어받는다), popstate 는 e.state.idx 로 이동 폭을 계산한다.
  // dirty 상태의 뒤로/앞으로: capture 단계에서 htmx 복원을 막고(stopImmediatePropagation) delta = 새 idx − 현재 idx 로 history.go(−delta) 해 원위치시키고
  // (그때의 popstate 는 htmx 에 넘기지 않음, pushState 없음 → 앞으로 가기 기록 유지) 되돌림이 끝난 뒤 이동 확인을 띄운다. "머무르기"는 그대로,
  // "이동"은 일회성 통과 허가 + history.go(delta) 로 htmx 가 정상 복원한다(그 popstate 가 통과 허가를 소비).
  // 우리가 부른 go() 의 popstate 가 1초 안에 안 오면(범위 밖 등) 기대 상태(ignorePops·pendingAsk·통과 허가)를 정리한다.
  // idx 를 모르는 항목(번호가 없는 이력)은 pushState 로 직전 주소를 되살리고(앞으로 가기 기록이 잘릴 수 있음) "이동"은 새 boost 이동 — 이제 드문 경우.
  const stateIdx = function (st) { return st && typeof st.idx === 'number' ? st.idx : null; };
  const hist = window.history;
  const origPush = hist.pushState.bind(hist);
  const origReplace = hist.replaceState.bind(hist);
  let curIdx = stateIdx(hist.state) === null ? 0 : stateIdx(hist.state);
  hist.replaceState = function (state, title, url) {
    const cur = stateIdx(hist.state);
    const st = stateIdx(state) === null && cur !== null ? Object.assign({}, state, { idx: cur }) : state;
    return arguments.length > 2 ? origReplace(st, title, url) : origReplace(st, title);
  };
  hist.pushState = function (state, title, url) {
    const base = stateIdx(hist.state);
    curIdx = (base !== null ? base : curIdx) + 1;
    const st = Object.assign({}, state, { idx: curIdx });
    return arguments.length > 2 ? origPush(st, title, url) : origPush(st, title);
  };
  try { hist.replaceState(hist.state || {}, ''); if (stateIdx(hist.state) === null) origReplace(Object.assign({}, hist.state, { idx: curIdx }), ''); } catch (err) { /* 상태를 못 쓰는 환경: idx 없음 → 안전한 쪽 */ }

  let ignorePops = 0; // 우리가 낸 history.go 가 일으킬 popstate 수(이 popstate 는 htmx 에 넘기지 않는다)
  let pendingAsk = null; // 되돌림이 끝난 뒤 띄울 이동 확인
  let expectTimer = null;
  let stableUrl = window.location.href;
  function clearExpect() { clearTimeout(expectTimer); expectTimer = null; }
  // history.go 를 부르고 popstate 가 오는지 지켜본다. 안 오면 기대 상태를 정리한다.
  function goExpecting(n) {
    clearExpect();
    expectTimer = setTimeout(function () {
      expectTimer = null;
      ignorePops = 0;
      pendingAsk = null;
      if (window.Alpine) Alpine.store('dirty').endPass();
    }, 1000);
    window.history.go(n);
  }
  window.adminHistoryGo = goExpecting;
  window.addEventListener('popstate', function (e) {
    clearExpect();
    const target = window.location.href;
    if (ignorePops > 0) { // 우리가 되돌린 popstate: htmx 에 넘기지 않고, 대기 중인 이동 확인을 띄운다.
      ignorePops -= 1;
      e.stopImmediatePropagation();
      if (ignorePops === 0 && pendingAsk) { const a = pendingAsk; pendingAsk = null; window.dispatchEvent(new CustomEvent('unsaved-ask', { detail: a })); }
      return;
    }
    const newIdx = stateIdx(e.state === undefined ? window.history.state : e.state);
    if (!window.Alpine) { if (newIdx !== null) curIdx = newIdx; stableUrl = target; return; }
    const s = Alpine.store('dirty');
    const t = new URL(target, window.location.href);
    const c = new URL(stableUrl, window.location.href);
    if (!s.needsGuard() || (t.pathname === c.pathname && t.search === c.search)) {
      if (s.passing()) s.endPass(); // "이동" 으로 진행하는 POP: 통과 허가 소비
      if (newIdx !== null) curIdx = newIdx;
      stableUrl = target;
      return;
    }
    e.stopImmediatePropagation();
    if (newIdx !== null && newIdx !== curIdx) {
      ignorePops += 1;
      pendingAsk = { href: t.href, delta: newIdx - curIdx };
      goExpecting(curIdx - newIdx);
      return;
    }
    // idx 를 모르는 항목: pushState 로 직전 주소를 되살리고 이동 확인(이동은 새 boost 이동).
    window.history.pushState(window.history.state, '', stableUrl);
    window.dispatchEvent(new CustomEvent('unsaved-ask', { detail: { href: t.href } }));
  }, true);
  document.addEventListener('htmx:pushedIntoHistory', function () { stableUrl = window.location.href; });
  document.addEventListener('htmx:afterSettle', function () { stableUrl = window.location.href; });

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

  // ── CSV 다운로드 공통 처리(ruling L) ────────────────────
  // `a[data-csv-download]` 클릭을 가로채 fetch → Blob → 임시 링크로 저장한다. 실패(HTTP 오류·네트워크)는 토스트로 알린다
  // (원본은 CSV 를 클라이언트에서 만들고 로드 오류를 토스트했다). href 는 그대로라 JS 가 없으면 일반 다운로드. 파일명은 Content-Disposition.
  function csvFilename(res, a) {
    const cd = res.headers.get('Content-Disposition') || '';
    const m = /filename="?([^";]+)"?/i.exec(cd);
    return (m && m[1]) || a.getAttribute('download') || 'download.csv';
  }
  function csvToast(e) {
    const d = describeFetchError(e);
    window.dispatchEvent(new CustomEvent('toast', { detail: { kind: 'error', title: 'CSV 를 내려받지 못했습니다', message: d.title + (d.detail ? '. ' + d.detail : '') } }));
  }
  async function csvDownload(a) {
    if (a.getAttribute('aria-busy') === 'true') return; // 연타 방지
    a.setAttribute('aria-busy', 'true');
    try {
      const res = await fetch(a.href, { credentials: 'same-origin', redirect: 'follow' });
      if (res.redirected || !res.ok) {
        const err = new Error('HTTP ' + res.status);
        err.httpStatus = res.redirected ? 401 : res.status;
        throw err;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const t = document.createElement('a');
      t.href = url;
      t.download = csvFilename(res, a);
      document.body.appendChild(t);
      t.click();
      t.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      csvToast(e);
    } finally {
      a.removeAttribute('aria-busy');
    }
  }
  window.adminCsvDownload = csvDownload;
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = e.target && e.target.closest ? e.target.closest('a[data-csv-download]') : null;
    if (!a) return;
    e.preventDefault();
    void csvDownload(a);
  });

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
      pass: false,
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
      // 이동 확인에서 "이동"을 누른 뒤 다음 이동 한 번(boost 요청 또는 beforeunload)이 경고 없이 통과. 쓰이면 endPass() 로 소비한다.
      allow() { this.pass = true; },
      passing() { return this.pass; },
      endPass() { this.pass = false; },
      // 이동 확인 문구를 바꾸는 화면(예: API 키 발급 완료 다이얼로그): {title, body} 또는 null(기본 문구). unsavedGuard 가 읽는다.
      notice: null,
      reset() { this.manual = false; this.parts = {}; this.disarmed = false; this.pass = false; this.notice = null; this.refresh(); },
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
        // 입력하는 즉시 현재 결과를 오래된 것으로 본다: 선택 표시를 지우고, 결과의 data-q 가 입력값과 같아질 때까지 Enter·클릭으로 실행하지 못한다
        // (150ms 디바운스 동안 이전 결과의 항목을 실행하는 일을 막는다).
        onInput() {
          // 입력이 현재 결과의 검색어로 돌아왔으면(150ms 안에 지우고 되돌림 → 요청 없음) 결과가 다시 유효하다.
          const m = this.$refs.list.querySelector('[data-results-marker]');
          if (m && (m.dataset.q || '') === this.$refs.input.value) {
            this.$refs.list.removeAttribute('aria-busy');
            this.apply();
            return;
          }
          this.options().forEach((o) => o.setAttribute('aria-selected', 'false'));
          this.$refs.input.removeAttribute('aria-activedescendant');
          this.$refs.list.setAttribute('aria-busy', 'true');
        },
        // 이 결과가 지금 입력값에 대한 것인지.
        // 빈 검색어는 속성이 생략될 수 있어(빈 값) 없으면 ''로 본다.
        fresh(o) { return (o.dataset.q || '') === this.$refs.input.value; },
        // 결과가 바뀌면(검색어·다시 확인) 첫 실행 가능한 항목을 선택한다.
        afterSwap(e) {
          if (e.target !== this.$refs.list) return;
          this.$refs.list.removeAttribute('aria-busy');
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
          if (os.length && !this.fresh(os[0])) return; // 오래된 결과에서는 이동하지 않는다
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
            if (o && !o.disabled && this.fresh(o)) this.run(o);
          }
        },
        optionOf(e) { return e.target && e.target.closest ? e.target.closest('[role="option"]') : null; },
        hover(e) {
          const o = this.optionOf(e);
          if (o && !o.disabled && this.fresh(o)) { this.sel = this.options().indexOf(o); this.apply(); }
        },
        activate(e) {
          const o = this.optionOf(e);
          if (o && !o.disabled && this.fresh(o)) this.run(o);
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
            // 사용자 동작이 만든 요청만 리셋한다: 트리거 이벤트가 실제(isTrusted) 입력이어야 한다. htmx 가 만드는 `load`·`every`(폴링) 트리거는
            // 합성 이벤트라 자동으로 제외되므로 폴링 요소에 data-quiet 이 없어도 안전하다. data-quiet 은 이중 안전장치.
            const cfg = (e.detail && e.detail.requestConfig) || {};
            const ev = cfg.triggeringEvent;
            if (!ev || ev.isTrusted !== true) return;
            const el = e.detail && e.detail.elt;
            if (el && el.hasAttribute && el.hasAttribute('data-quiet')) return;
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

    // ── 활동 화면(ActivityPage.tsx) ───────────────────────────
    // 공유 1초 틱: 구독자가 있을 때만 interval 하나가 돈다(행마다 interval 을 두지 않는다). 구독 해제는 컴포넌트 destroy(요소가 교체·이동으로 사라질 때).
    const activityTicker = {
      subs: new Set(),
      id: null,
      add(fn) {
        this.subs.add(fn);
        if (this.id === null) this.id = setInterval(() => { const t = Date.now(); this.subs.forEach((f) => f(t)); }, 1000);
        return () => {
          this.subs.delete(fn);
          if (this.subs.size === 0 && this.id !== null) { clearInterval(this.id); this.id = null; }
        };
      },
    };
    const ACT = '/admin/_/activity/';
    function actKind(e) {
      const pi = e.detail && e.detail.pathInfo;
      const p = pi ? String(pi.requestPath || pi.finalRequestPath || '') : '';
      if (p.indexOf(ACT + 'rows') === 0) return 'rows';
      if (p.indexOf(ACT + 'poll') === 0) return 'poll';
      if (p.indexOf(ACT + 'more') === 0) return 'more';
      if (p.indexOf(ACT + 'export') === 0) return 'export';
      return p.indexOf(ACT) === 0 ? 'detail' : '';
    }
    // 폴링·새로고침·필터 교체의 결과를 상태 표시로 알린다(토스트 중복 없음). 목록 자체는 서버가 htmx 로 바꾼다.
    // 성공 → 실시간, 받은 뒤의 폴링 실패 → "연결 끊김"(목록 유지), 필터 교체 서버 거절(422) → 목록 영역의 오류 카드(loaded=false).
    // 중단된 요청(hx-sync replace)은 responseError/sendError/timeout 이 오지 않아 실패로 세지 않는다.
    Alpine.data('activityLive', function () {
      return {
        loaded: false,
        disconnected: false,
        lastOkAt: null,
        now: Date.now(),
        pollSec: 5,
        selectedId: null,
        detailState: 'idle', // idle | loading | netError
        detailUrl: null,
        qv: '', // 액션 검색어(trim) — 칩·빈 결과 판단에 쓴다
        noMatch: false,
        rowsInflight: 0,
        rowsBusy: false, // 필터 교체 중에는 "더 보기"를 막는다(새 필터에 옛 커서를 싣지 않게)
        unsub: null,
        init() {
          this.loaded = this.$el.dataset.loaded === 'true';
          this.pollSec = parseInt(this.$el.dataset.pollSec, 10) || 5;
          if (this.loaded) this.lastOkAt = Date.now();
          this.unsub = activityTicker.add((t) => { this.now = t; });
        },
        destroy() { if (this.unsub) this.unsub(); },
        get liveText() {
          const ago = this.lastOkAt === null ? '' : ' · ' + Math.max(0, Math.floor((this.now - this.lastOkAt) / 1000)) + '초 전 갱신';
          return (this.disconnected ? '연결 끊김' : '실시간') + ago;
        },
        // 마지막으로 받은 시각(KST HH:mm:ss) — "아래 목록은 12:00:00 기준입니다"
        get sinceText() {
          return this.lastOkAt === null ? '' : new Date(this.lastOkAt + 9 * 3600 * 1000).toISOString().slice(11, 19);
        },
        beforeRequest(e) {
          // 필터를 바꾸면 이전 조건의 "더 보기" 응답이 새 목록에 붙지 않게 중단한다.
          if (actKind(e) === 'rows') {
            this.rowsInflight++;
            this.rowsBusy = true;
            const m = document.getElementById('activity-more');
            if (m) htmx.trigger(m, 'htmx:abort');
          }
        },
        afterRequest(e) {
          const k = actKind(e);
          const ok = e.detail.successful === true;
          if (k === 'rows') {
            this.rowsInflight = Math.max(0, this.rowsInflight - 1);
            this.rowsBusy = this.rowsInflight > 0;
          }
          if ((k === 'rows' || k === 'poll') && ok) {
            this.loaded = true;
            this.disconnected = false;
            this.lastOkAt = Date.now();
            this.now = this.lastOkAt;
          } else if (k === 'detail') {
            const st = e.detail.xhr && e.detail.xhr.status;
            if (ok || st === 422) this.detailState = 'idle'; // 422 = 패널 안 오류 조각이 보인다
          }
        },
        failed(e) {
          const k = actKind(e);
          const st = e.detail && e.detail.xhr ? e.detail.xhr.status : 0;
          if (k === 'rows') {
            // 필터 전환이 실패했다 — 컨트롤은 새 필터인데 목록은 옛 것이다. 다음 성공한 요청(폴링 포함)이 병합이 아니라 교체하도록 stale 표시.
            const l = document.getElementById('activity-loaded');
            if (l) l.value = '0';
            if (st === 422) { this.loaded = false; this.disconnected = false; }
          }
          else if (k === 'poll' && this.loaded) this.disconnected = true;
          else if (k === 'detail' && st !== 422) this.detailState = 'netError';
        },
        // 카테고리 세그먼트: 요청이 읽는 숨은 입력을 바로 맞춘다(응답이 오기 전에 tenant·검색 요청이 나가도 현재 선택을 싣게).
        catChanged(e) {
          const el = document.getElementById('activity-cat');
          if (el && e.target && e.target.closest && e.target.closest('#activity-seg') && e.detail) el.value = e.detail.value;
        },
        // 액션 검색(원본 actionQuery): 이미 읽어 온 행을 클라이언트에서 거른다 — 코드(대소문자 무시) 또는 한글 라벨에 검색어(trim) 포함.
        // 행이 하나도 안 보이는 날짜 그룹은 머리글째 숨긴다. 폴링·더 보기·필터 교체가 행을 바꾼 뒤(after-settle)에도 다시 적용한다.
        applyFilter() {
          const inp = document.getElementById('activity-q');
          const t = (inp ? inp.value : '').trim();
          this.qv = t;
          const up = t.toUpperCase();
          const rows = document.querySelectorAll('#activity-feed .feed-row');
          let visible = 0;
          rows.forEach((r) => {
            const ok = !t || (r.dataset.type || '').indexOf(up) >= 0 || (r.dataset.label || '').indexOf(t) >= 0;
            r.hidden = !ok;
            if (ok) visible++;
          });
          document.querySelectorAll('#activity-feed .feed-group').forEach((g) => { g.hidden = !g.querySelector('.feed-row:not([hidden])'); });
          this.noMatch = rows.length > 0 && visible === 0;
        },
        clearQ() {
          const inp = document.getElementById('activity-q');
          if (inp) inp.value = '';
          this.applyFilter();
        },
        // 안전망: 이미 화면에 있는 행(data-id)은 OOB 로 들어오는 조각에서 뺀다(폴링·더 보기 경계에서 겹쳤을 때 중복 행 방지).
        dedupe(e) {
          const f = e.detail && e.detail.fragment;
          if (!f || !f.querySelectorAll) return;
          f.querySelectorAll('.feed-row[data-id]').forEach((r) => {
            const id = r.getAttribute('data-id');
            if (document.querySelector('#activity-feed .feed-row[data-id="' + id.replace(/"/g, '') + '"]')) r.remove();
          });
          f.querySelectorAll('.feed-group').forEach((g) => { if (!g.querySelector('.feed-row')) g.remove(); });
        },
        openDetail(id) {
          this.selectedId = id;
          this.detailState = 'loading';
          this.detailUrl = ACT + encodeURIComponent(id);
          const d = document.getElementById('activity-detail');
          if (d) d.replaceChildren();
        },
        closeDetail() {
          htmx.trigger('#activity-detail', 'htmx:abort'); // 불러오는 중에 닫으면 응답이 와도 다시 열리지 않는다
          this.selectedId = null;
          this.detailState = 'idle';
          const d = document.getElementById('activity-detail');
          if (d) d.replaceChildren();
        },
        retryDetail() {
          if (!this.detailUrl) return;
          this.detailState = 'loading';
          htmx.ajax('GET', this.detailUrl, { source: '#activity-detail', target: '#activity-detail', swap: 'innerHTML' }); // source: 요청 이벤트·hx-sync(this:replace)·data-quiet 가 이 요소 기준
        },
        // CSV: 화면에 읽어 온 행(가장 오래된 행의 커서까지)을 내보낸다. 읽은 행이 없으면 원본처럼 경고만.
        exportClick(e) {
          const el = e.currentTarget;
          const newest = document.getElementById('activity-newest');
          if (!this.loaded) { e.preventDefault(); return; }
          if (!newest || !newest.value) {
            e.preventDefault();
            window.dispatchEvent(new CustomEvent('toast', { detail: { kind: 'warn', title: '내보낼 데이터 없음' } }));
            return;
          }
          const bid = document.getElementById('activity-before-id');
          const cat = document.getElementById('activity-cat');
          const ten = document.getElementById('activity-tenant');
          const q = new URLSearchParams();
          q.set('category', cat ? cat.value : 'all');
          if (ten && ten.value) q.set('tenantId', ten.value);
          if (bid && bid.value) q.set('beforeId', bid.value);
          el.setAttribute('href', ACT + 'export?' + q.toString());
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
