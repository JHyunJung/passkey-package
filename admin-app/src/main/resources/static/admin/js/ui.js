// 공통 UI 조각의 Alpine 컴포넌트 (← admin-ui/src/ui/*.tsx). 템플릿: templates/admin/fragments/ui/*.html
// Alpine 보다 먼저 로드한다(alpine:init 에서 등록). 포커스 트랩은 @alpinejs/focus 의 x-trap 을 쓴다.

// HX-Redirect 와 함께 온 토스트는 이 페이지를 곧 떠나므로 sessionStorage 로 다음 화면에 넘긴다(toastHost.init 이 한 번 꺼내 띄운다).
// 같은 응답에 HX-Redirect 가 있을 때만 저장한다(beforeOnLoad 는 htmx 가 HX-Trigger·HX-Redirect 를 처리하기 전에 온다).
// 로그인·MFA 화면(auth.js 는 이 파일도 싣는다)과 공통 틀 화면(Tenant 생성 등)이 같은 규칙을 쓴다.
document.addEventListener('htmx:beforeOnLoad', function (e) {
  const xhr = e.detail && e.detail.xhr;
  if (!xhr || !xhr.getResponseHeader || !xhr.getResponseHeader('HX-Redirect')) return;
  const trigger = xhr.getResponseHeader('HX-Trigger');
  if (!trigger) return;
  let toast = null;
  try { toast = JSON.parse(trigger).toast; } catch (err) { toast = null; }
  if (!toast) return;
  try { window.sessionStorage.setItem('admin.pendingToast', JSON.stringify(toast)); } catch (err) { /* 저장소를 못 쓰면 토스트만 생략 */ }
});
// 탭 링크(role=tab + data-tab, hx-get 으로 조각 교체)가 성공하면 활성 표시·로빙 tabindex·마지막 브레드크럼을 맞춘다.
// 서버 조각은 탭 내용만 돌려주므로(다른 탭 task 의 컨트롤러도 같다) 활성 표시는 여기서 한다. 실패·중단된 요청은 바꾸지 않는다.
document.addEventListener('htmx:afterRequest', function (e) {
  const d = e.detail || {};
  const a = d.elt && d.elt.closest ? d.elt.closest('[role="tab"][data-tab]') : null;
  if (!a || !d.successful) return;
  const list = a.closest('[role="tablist"]');
  if (!list) return;
  list.querySelectorAll('[role="tab"]').forEach(function (t) {
    const on = t === a;
    t.classList.toggle('tabs__btn--active', on);
    t.setAttribute('aria-selected', on ? 'true' : 'false');
    t.setAttribute('tabindex', on ? '0' : '-1');
  });
  const cur = document.querySelector('.crumbs__current');
  if (cur && a.dataset.label) cur.textContent = a.dataset.label;
});
document.addEventListener('alpine:init', () => {
  /** 클립보드 복사(lib/clipboard.ts). secure context 가 아니면 execCommand 폴백. */
  async function copyToClipboard(text) {
    if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
      try { await navigator.clipboard.writeText(text); return true; } catch (e) { /* 폴백 */ }
    }
    let ta = null;
    try {
      ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      document.body.appendChild(ta);
      ta.select();
      return document.execCommand('copy');
    } catch (e) {
      return false;
    } finally {
      if (ta) ta.remove();
    }
  }
  window.adminCopy = copyToClipboard;

  function isConfirm(e) { return !!(e.target && e.target.hasAttribute && e.target.hasAttribute('data-confirm')); }

  /** 오류 응답의 쉬운 문구: HX-Trigger 의 confirmError(확인 요청, X-Confirm) → toast(호환) → 기본 문구. */
  function toastMessage(xhr) {
    try {
      const h = xhr && xhr.getResponseHeader && xhr.getResponseHeader('HX-Trigger');
      if (h) {
        const t = JSON.parse(h);
        const m = (t.confirmError && t.confirmError.message) || (t.toast && t.toast.message);
        if (m) return m;
      }
    } catch (err) { /* 기본 문구 */ }
    if (xhr && xhr.status === 0) return '서버에 연결하지 못했거나 응답이 오지 않았습니다. 연결 상태를 확인한 뒤 다시 시도하세요.'; // 전송 실패·시간 초과
    return '요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.';
  }

  /** 가장 가까운 form 의 reset 이벤트에 맞춰 Alpine 상태를 처음으로 되돌린다(SaveBar 되돌리기). */
  function onFormReset(root, fn) {
    const f = root.closest && root.closest('form');
    if (f) f.addEventListener('reset', fn);
  }

  // ── Dialog / ConfirmDialog ──────────────────────────────
  // 열기: 같은 x-data 범위에서 open=true, 또는 window 이벤트 `dialog:open` {id}. 닫기: `dialog:close` {id}.
  // Escape 와 스크림 클릭으로 닫힘(dismissible=false 이면 무시), 닫은 뒤 포커스는 열기 전 요소로 복귀.
  Alpine.data('dialog', (cfg = {}) => ({
    id: cfg.id || '',
    open: !!cfg.open,
    dismissible: cfg.dismissible !== false,
    closeOnScrim: cfg.closeOnScrim !== false,
    busy: false,
    prev: null,
    // ConfirmDialog 확인 절차(L3): 이름 입력(typing) 또는 확인 체크(ack)를 통과해야 확인 버튼이 켜진다.
    level: cfg.level || 'L1',
    typing: !!cfg.typing,
    ack: !!cfg.ack,
    typed: false,
    acked: false,
    verb: cfg.verb || '실행',
    // ConfirmDialog 실패 표시(ConfirmDialog.tsx): 닫지 않고 안에서 `<verb>하지 못했습니다` + 쉬운 설명을 보여 주고 재시도하게 한다.
    error: null,
    get gateOk() { return this.level !== 'L3' || (this.typing ? this.typed : this.ack ? this.acked : true); },
    init() {
      this.$watch('open', (v) => {
        if (v) {
          this.prev = document.activeElement; this.busy = false; this.typed = false; this.acked = false; this.error = null;
          this.processHtmx();
        }
        else if (this.prev && this.prev.focus) { const p = this.prev; this.prev = null; this.$nextTick(() => p.focus()); }
      });
      if (this.open) { this.prev = document.activeElement; this.processHtmx(); }
    },
    // <template x-if> 로 그려진 안쪽의 hx-* 는 htmx 가 DOM 변이를 관찰하지 않아 처리되지 않는다 → 열린 뒤 직접 process 한다(initHash 로 중복 처리는 무해).
    processHtmx() { this.$nextTick(() => { if (window.htmx) window.htmx.process(this.$root); }); },
    // 확인 버튼(data-confirm)의 htmx 요청만 본다. 성공이면 닫고, 실패(4xx/5xx·네트워크)면 열어 둔 채 오류를 보인다.
    onBeforeRequest(e) { if (isConfirm(e)) { this.busy = true; this.error = null; } },
    onAfterRequest(e) {
      if (!isConfirm(e)) return;
      this.busy = false;
      if (e.detail && e.detail.successful) { this.open = false; return; }
      this.error = { title: this.verb + '하지 못했습니다', text: toastMessage(e.detail && e.detail.xhr) };
    },
    onOpen(e) { if (!this.id || (e.detail && e.detail.id === this.id)) this.open = true; },
    onClose(e) { if (!this.id || !e.detail || e.detail.id === this.id) this.open = false; },
    close() { if (this.dismissible && !this.busy) this.open = false; },
    onEscape() { if (this.open) this.close(); },
    onScrim(e) { if (e.target === e.currentTarget && this.closeOnScrim) this.close(); },
  }));

  // ── Menu ───────────────────────────────────────────────
  const ITEM_H = 36, REASON_ITEM_H = 52, GAP = 6;
  function placeMenu(t, h) {
    const right = window.innerWidth - t.right;
    const below = window.innerHeight - t.bottom - GAP;
    const above = t.top - GAP;
    const px = (n) => n + 'px';
    if (h <= below) return { position: 'fixed', top: px(t.bottom + GAP), bottom: 'auto', right: px(right) };
    if (h <= above) return { position: 'fixed', top: 'auto', bottom: px(window.innerHeight - t.top + GAP), right: px(right) };
    if (below >= above) return { position: 'fixed', top: px(t.bottom + GAP), bottom: 'auto', right: px(right), maxHeight: px(below), overflowY: 'auto' };
    return { position: 'fixed', top: 'auto', bottom: px(window.innerHeight - t.top + GAP), right: px(right), maxHeight: px(above), overflowY: 'auto' };
  }
  Alpine.data('menu', () => ({
    open: false,
    pos: {},
    items() { return Array.from(this.$root.querySelectorAll('[role="menuitem"]')); },
    estimate() {
      const its = this.items();
      const body = its.reduce((h, el) => h + (el.getAttribute('aria-disabled') === 'true' && el.querySelector('.hint') ? REASON_ITEM_H : ITEM_H), 0);
      return 12 + body + (this.$root.querySelector('.menu__sep') ? 35 : 0);
    },
    toggle() {
      if (this.open) { this.close(false); return; }
      const trigger = this.$refs.trigger;
      const r = trigger.getBoundingClientRect();
      this.pos = placeMenu(r, this.estimate());
      this.open = true;
      this.$nextTick(() => {
        // 추정으로 먼저 연 뒤 렌더된 높이로 방향을 다시 정한다.
        const menu = this.$refs.menu;
        if (menu) {
          const h = menu.scrollHeight + menu.offsetHeight - menu.clientHeight;
          if (h > 0) this.pos = placeMenu(trigger.getBoundingClientRect(), h);
        }
        const first = this.items()[0];
        if (first) first.focus({ preventScroll: true });
      });
    },
    close(refocus) {
      this.open = false;
      if (refocus && this.$refs.trigger) this.$refs.trigger.focus();
    },
    outside(e) { if (this.open && !this.$root.contains(e.target)) this.open = false; },
    // 잘린 메뉴 자신의 스크롤은 무시하고, 페이지·다른 컨테이너 스크롤에만 닫는다.
    dismiss(e) { if (this.open && !(e.target instanceof Node && this.$refs.menu && this.$refs.menu.contains(e.target))) this.open = false; },
    onKeydown(e) {
      if (e.key === 'Escape') { if (this.open) { e.preventDefault(); e.stopPropagation(); this.close(true); } return; }
      if (!this.open) return;
      const els = this.items();
      const i = els.indexOf(document.activeElement);
      if (e.key === 'ArrowDown') { e.preventDefault(); els[(i + 1) % els.length]?.focus(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); els[i < 0 ? els.length - 1 : (i - 1 + els.length) % els.length]?.focus(); }
      else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); this.select(els[i]); }
    },
    // 비활성 항목은 보이되 실행·닫힘 없음. 활성 항목은 클릭(htmx 요청 등)을 실행하고 닫는다.
    select(el) {
      if (el.getAttribute('aria-disabled') === 'true') return;
      el.click();
      this.close(true);
    },
    onItemClick(e) {
      const el = e.currentTarget;
      e.stopPropagation();
      if (el.getAttribute('aria-disabled') === 'true') { e.preventDefault(); e.stopImmediatePropagation(); return; }
      this.close(true);
    },
  }));

  // ── Tabs ───────────────────────────────────────────────
  // role=tab 링크(?tab=…)의 로빙 tabindex + 방향키/Home/End 이동(이동하면 해당 탭을 활성화=클릭).
  Alpine.data('tabs', () => ({
    onKeydown(e) {
      const tabs = Array.from(this.$root.querySelectorAll('[role="tab"]'));
      const i = tabs.indexOf(e.target.closest('[role="tab"]'));
      if (i < 0) return;
      const last = tabs.length - 1;
      const next = e.key === 'ArrowRight' ? (i === last ? 0 : i + 1)
        : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1)
        : e.key === 'Home' ? 0
        : e.key === 'End' ? last : null;
      if (next === null) return;
      e.preventDefault();
      tabs[next].focus();
      tabs[next].click();
    },
  }));

  // ── ChipInput ──────────────────────────────────────────
  // values 는 form 으로 함께 제출되도록 hidden input(name)으로 내보낸다.
  // 검증: cfg.validate(함수) 또는 cfg.validateUrl(GET ?value=…&existing=… → 응답 본문이 오류 문구, 비면 통과).
  Alpine.data('chipInput', (cfg = {}) => ({
    name: cfg.name || '',
    mono: !!cfg.mono,
    values: Array.isArray(cfg.values) ? [...cfg.values] : [],
    added: cfg.added || [],
    draft: '',
    error: null,
    disabled: !!cfg.disabled,
    validating: false,
    gen: 0, // 되돌리기마다 올려, 이미 보낸 검증 응답이 늦게 와도 버려진 초안을 추가하지 못하게 한다
    init() {
      const initial = Array.isArray(cfg.values) ? [...cfg.values] : [];
      onFormReset(this.$root, () => { this.gen += 1; this.validating = false; this.values = [...initial]; this.draft = ''; this.error = null; });
    },
    async onEnter() {
      if (this.disabled || this.validating) return;
      const raw = this.draft.trim();
      if (!raw) return;
      let err = null;
      if (typeof cfg.validate === 'function') err = cfg.validate(raw, this.values) || null;
      else if (cfg.validateUrl) {
        // 검증을 못 하면 통과로 보지 않는다: 초안을 남기고 재시도 가능한 오류만 보인다.
        this.validating = true;
        const gen = this.gen;
        try {
          const q = new URLSearchParams({ value: raw });
          this.values.forEach((v) => q.append('existing', v));
          const res = await fetch(cfg.validateUrl + (cfg.validateUrl.includes('?') ? '&' : '?') + q, { headers: { 'HX-Request': 'true' }, credentials: 'same-origin' });
          if (gen !== this.gen) return;
          if (!res.ok) { this.error = '검증하지 못했습니다. 잠시 후 다시 시도하세요.'; return; }
          const text = (await res.text()).trim();
          if (gen !== this.gen) return;
          err = text || null;
        } catch (e) {
          if (gen === this.gen) this.error = '검증하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도하세요.';
          return;
        } finally {
          if (gen === this.gen) this.validating = false;
        }
      }
      if (err) { this.error = err; return; }
      const v = typeof cfg.normalize === 'function' ? cfg.normalize(raw) : raw;
      this.error = null;
      this.draft = '';
      if (!this.values.includes(v)) { this.values.push(v); this.changed(); }
    },
    remove(v) { this.values = this.values.filter((x) => x !== v); this.changed(); },
    changed() { this.$dispatch('chips-change', { values: [...this.values] }); },
    onInput() { if (this.error) this.error = null; },
  }));

  // ── OtpInput ───────────────────────────────────────────
  Alpine.data('otpInput', (cfg = {}) => ({
    length: cfg.length || 6,
    value: cfg.value || '',
    cells() { return Array.from(this.$root.querySelectorAll('.otp__cell')); },
    digit(i) { return this.value[i] || ''; },
    focus(i) { const c = this.cells()[Math.min(i, this.length - 1)]; if (c) c.focus(); },
    commit(next, focusIndex) {
      this.value = next.replace(/\D/g, '').slice(0, this.length);
      this.cells().forEach((c, i) => { c.value = this.digit(i); });
      this.focus(focusIndex);
      if (this.value.length === this.length) {
        this.$dispatch('otp-complete', { value: this.value });
        if (cfg.submit) { const f = this.$root.closest('form'); if (f) this.$nextTick(() => f.requestSubmit()); }
      }
    },
    onInput(i, e) {
      const typed = e.target.value.replace(/\D/g, '');
      if (typed.length === 0) {
        // filled 칸에서 Backspace: 이 칸과 뒤 값을 비우고 같은 칸에 포커스를 유지한다.
        this.value = this.value.slice(0, i);
        this.cells().forEach((c, k) => { c.value = this.digit(k); });
        this.focus(i);
        return;
      }
      if (typed.length >= 2) { this.commit(typed, typed.length); return; }
      const target = Math.min(i, this.value.length);
      this.commit(this.value.slice(0, target) + typed + this.value.slice(target + 1), target + 1);
    },
    onPaste(e) {
      e.preventDefault();
      const only = (e.clipboardData.getData('text') || '').replace(/\D/g, '').slice(0, this.length);
      if (!only) return;
      this.commit(only, only.length);
    },
    onKeydown(i, e) {
      if (e.key === 'Backspace' && !this.digit(i) && i > 0) {
        e.preventDefault();
        this.value = this.value.slice(0, i - 1);
        this.cells().forEach((c, k) => { c.value = this.digit(k); });
        this.focus(i - 1);
      } else if (e.key === 'ArrowLeft' && i > 0) this.focus(i - 1);
      else if (e.key === 'ArrowRight' && i < this.length - 1) this.focus(i + 1);
    },
    clear() { this.value = ''; this.cells().forEach((c) => { c.value = ''; }); this.focus(0); },
  }));

  // ── ConfirmByTyping ────────────────────────────────────
  // 일치 여부가 바뀔 때마다 `match-change`({detail: boolean})를 올려 보낸다(확인 버튼 활성화는 바깥 범위가 받는다).
  Alpine.data('confirmByTyping', (cfg = {}) => ({
    expected: cfg.expected || '',
    actionLabel: cfg.actionLabel || '실행',
    v: '',
    get ok() { return this.v === this.expected; },
    onInput() { this.$dispatch('match-change', this.ok); },
    get hint() { return this.ok ? '일치합니다' : `일치해야 ${this.actionLabel} 버튼이 활성화됩니다.`; },
  }));

  // ── CopyBtn / SecretBox ────────────────────────────────
  Alpine.data('copyBtn', (cfg = {}) => ({
    value: cfg.value || '',
    label: cfg.label || '복사',
    copied: false,
    timer: null,
    async copy(e) {
      if (e) e.stopPropagation();
      if (await copyToClipboard(this.value)) {
        this.copied = true;
        this.$dispatch('copied', { value: this.value });
        clearTimeout(this.timer);
        this.timer = setTimeout(() => { this.copied = false; }, cfg.reset || 1500);
      }
    },
  }));

  // ── SaveBar / 저장하지 않은 변경 ───────────────────────
  // form 의 필드 값을 처음 상태와 비교해 바뀐 개수를 센다. 되돌리기=form.reset(), 저장=form.requestSubmit().
  // 변경 개수와 저장 중 여부는 전역 Alpine.store('dirty')(app.js)에 보고한다. 새로고침·탭 닫기 경고(beforeunload)와 이동 확인은
  // 그 스토어를 보는 app.js 의 리스너 하나·unsavedGuard 하나가 맡는다(이 컴포넌트는 따로 경고하지 않는다).
  let saveBarSeq = 0;
  function formSnapshot(form) {
    const snap = {};
    new FormData(form).forEach((v, k) => { (snap[k] = snap[k] || []).push(String(v)); });
    return snap;
  }
  /** htmx 이벤트가 form 의 저장 요청인지: 출발 요소(detail.elt)가 form 자신이거나 form 안의 submit 버튼이고, verb 가 GET 이 아님. */
  function isOwnSave(e, f) {
    const d = e.detail || {};
    const elt = d.elt;
    const verb = d.requestConfig && d.requestConfig.verb;
    if (!elt || !verb || String(verb).toLowerCase() === 'get') return false;
    if (elt === f) return true;
    return f.contains(elt) && !!elt.matches && elt.matches('button[type="submit"], input[type="submit"]');
  }

  Alpine.data('saveBar', (cfg = {}) => ({
    form: null,
    base: {},
    count: 0,
    saveLabel: cfg.saveLabel || '저장',
    saving: false,
    uid: 'savebar-' + (++saveBarSeq),
    inflight: new Map(),
    pending: new Map(), // after-request 리스너 → 그 리스너를 단 출발 요소
    init() {
      this.form = cfg.form ? document.getElementById(cfg.form) : this.$root.closest('form');
      if (!this.form) return;
      const f = this.form;
      this.base = formSnapshot(f);
      f.addEventListener('input', () => this.recount());
      f.addEventListener('change', () => this.recount());
      f.addEventListener('chips-change', () => this.$nextTick(() => this.recount()));
      f.addEventListener('switch-change', () => this.$nextTick(() => this.recount()));
      f.addEventListener('segmented-change', () => this.$nextTick(() => this.recount()));
      f.addEventListener('reset', () => this.$nextTick(() => this.recount()));
      // htmx 제출: 저장 중에만 막고, 실패하면 다시 켜서 재시도·경고가 가능하게 한다. 기준선은 성공이 확인된 뒤에만 갱신.
      // form 자신의 저장 요청(비-GET: form 자체 또는 form 안 submit 버튼이 보낸 것)만 본다. 폼 안의 GET(Segmented 의 hx-get 등)과
      // 그 밖의 요청은 saving·기준선에 영향이 없다. 기준선은 제출 시점의 값을 요청(requestConfig)에 묶어 두었다가,
      // 성공이 확인된 요청의 스냅샷으로만 갱신한다(저장 중에 더 고친 값은 계속 변경으로 남고, 동시 요청이 서로 덮지 않는다).
      // 완료는 추적 중인 requestConfig 로만 판정한다(출처 재검사 없음). htmx 2.0.4 는 afterRequest 를 항상 원래 출발 요소에
      // 먼저 보내고(성공 스왑으로 떨어져 나가도, 실패·abort·timeout·sendError 경로도 모두 afterRequest 를 먼저 보냄),
      // 떨어져 나간 경우에만 살아남은 조상에 다시 보낸다. 그래서 출발 요소에 직접 리스너를 단다: 분리돼도 받는다.
      f.addEventListener('htmx:before-request', (e) => {
        if (!isOwnSave(e, f)) return;
        const key = e.detail.requestConfig;
        const src = e.detail.elt;
        const done = (ev) => {
          if (!ev.detail || ev.detail.requestConfig !== key) return;
          src.removeEventListener('htmx:after-request', done);
          this.pending.delete(done);
          this.finish(key, !!ev.detail.successful);
        };
        this.inflight.set(key, formSnapshot(f));
        this.saving = true;
        this.report();
        src.addEventListener('htmx:after-request', done);
        this.pending.set(done, src);
        // 다른 리스너가 요청을 취소하면(beforeRequest preventDefault) afterRequest 가 오지 않으므로 바로 정리한다.
        queueMicrotask(() => { if (e.defaultPrevented) done({ detail: { requestConfig: key, successful: false } }); });
      });
      // 일반(비 htmx) 제출은 페이지 이동이므로 경고를 끈다.
      f.addEventListener('submit', () => { if (!f.hasAttribute('hx-post') && !f.hasAttribute('hx-put') && !f.hasAttribute('hx-patch') && !f.hasAttribute('hx-delete')) Alpine.store('dirty').disarm(); });
    },
    destroy() {
      this.pending.forEach((src, fn) => src.removeEventListener('htmx:after-request', fn));
      this.pending.clear();
      Alpine.store('dirty').report(this.uid, 0, false); // 이 화면이 사라지면 더는 변경으로 세지 않는다
    },
    /** 전역 dirty 스토어에 이 폼의 변경 수·저장 중 여부를 알린다. */
    report() { Alpine.store('dirty').report(this.uid, this.count, this.saving); },
    /** 추적 중인 저장 요청 하나를 끝낸다. 성공이면 그 요청의 제출 시점 스냅샷이 새 기준선. */
    finish(key, ok) {
      const snap = this.inflight.get(key);
      if (!snap) return;
      this.inflight.delete(key);
      this.saving = this.inflight.size > 0;
      if (ok) { this.base = snap; this.recount(); } else this.report();
    },
    recount() {
      const now = formSnapshot(this.form);
      const keys = new Set([...Object.keys(this.base), ...Object.keys(now)]);
      let n = 0;
      keys.forEach((k) => { if (JSON.stringify(this.base[k] || []) !== JSON.stringify(now[k] || [])) n += 1; });
      this.count = n;
      this.report();
      this.$dispatch('unsaved-change', { count: n });
    },
    revert() { if (this.form) this.form.reset(); },
    save() { if (this.form) this.form.requestSubmit(); },
    get dirty() { return this.count > 0; },
  }));

  // ── Switch / Segmented ─────────────────────────────────
  // 로컬 상태가 aria-checked / aria-pressed 와 form 제출용 hidden input 값을 정한다. form reset 이면 처음 값으로.
  Alpine.data('switchToggle', (cfg = {}) => ({
    on: !!cfg.checked,
    init() {
      const initial = !!cfg.checked;
      onFormReset(this.$root, () => { this.on = initial; });
      this.$watch('on', (v) => this.$dispatch('switch-change', { checked: v }));
    },
  }));
  Alpine.data('segmented', (cfg = {}) => ({
    value: cfg.value == null ? '' : String(cfg.value),
    init() {
      const initial = this.value;
      onFormReset(this.$root, () => { this.value = initial; });
    },
    select(v) {
      if (v === this.value) return;
      this.value = v;
      this.$dispatch('segmented-change', { value: v });
    },
  }));

  // ── ToastHost ──────────────────────────────────────────
  // 서버가 HX-Trigger: {"toast":{"kind":"success|error|warn","message":"…","title":"…"}} 로 보낸 이벤트를 받는다.
  // 오류 토스트는 남고(role=alert), 그 밖은 5초 뒤 닫힌다. 마우스·포커스가 올라가 있으면 멈춘다.
  Alpine.data('toastHost', () => ({
    toasts: [],
    seq: 0,
    // 앞 화면이 이동 직전에 받은 토스트(위 htmx:beforeOnLoad: HX-Trigger + HX-Redirect 응답)를 한 번 띄운다.
    init() {
      let pending = null;
      try { pending = window.sessionStorage.getItem('admin.pendingToast'); window.sessionStorage.removeItem('admin.pendingToast'); } catch (e) { pending = null; }
      if (!pending) return;
      try { this.push(JSON.parse(pending)); } catch (e) { /* 깨진 값은 버린다 */ }
    },
    push(detail) {
      const d = detail || {};
      const kind = d.kind === 'error' || d.kind === 'err' ? 'err' : d.kind === 'warn' ? 'warn' : 'ok';
      const t = {
        id: ++this.seq, kind,
        title: d.title || d.message || '', message: d.title ? (d.message || '') : '',
        detail: d.detail || '', traceId: d.traceId || '',
        action: d.action || null, hover: false, focus: false, timer: null, duration: d.duration || 5000,
      };
      this.toasts.push(t);
      this.arm(t);
    },
    arm(t) {
      clearTimeout(t.timer);
      if (t.kind === 'err' || t.hover || t.focus) return;
      t.timer = setTimeout(() => this.close(t.id), t.duration);
    },
    pause(t, key, v) { t.hover = key === 'hover' ? v : t.hover; t.focus = key === 'focus' ? v : t.focus; this.arm(t); },
    close(id) { const t = this.toasts.find((x) => x.id === id); if (t) clearTimeout(t.timer); this.toasts = this.toasts.filter((x) => x.id !== id); },
    run(t) { if (t.action && t.action.href) window.location.assign(t.action.href); this.close(t.id); },
  }));

  // ── Skeleton ───────────────────────────────────────────
  Alpine.data('skeleton', (cfg = {}) => ({
    slow: false,
    t: null,
    init() { this.t = setTimeout(() => { this.slow = true; }, cfg.slowMs || 3000); },
    destroy() { clearTimeout(this.t); },
  }));

  // ── LoadError: 오류 정보 복사 ──────────────────────────
  Alpine.data('loadError', (cfg = {}) => ({
    state: 'idle',
    timer: null,
    labels: { idle: '오류 정보 복사', ok: '복사했습니다', fail: '복사하지 못했습니다' },
    async copy() {
      const ok = await copyToClipboard(cfg.text || '');
      this.state = ok ? 'ok' : 'fail';
      clearTimeout(this.timer);
      this.timer = setTimeout(() => { this.state = 'idle'; }, 2000);
    },
  }));

  // ── 저장하지 않은 변경 경고(이동 확인) ─────────────────
  // 확인창은 하나뿐이다(useUnsavedGuard). dirty 판단은 전역 Alpine.store('dirty')(app.js)가 한다.
  // 물음이 오는 길: (1) 같은 출처 링크 클릭(capture) (2) app.js 의 htmx:beforeRequest 가 hx-boost GET 이동을 취소하며 `unsaved-ask` 를 올림.
  // "이동"을 확정하면 스토어에 한 번만 통과 허가(allow)를 주고 같은 주소로 다시 이동한다(boost 이동이면 boost 로) — 새로고침 경고도 이때는 건너뛴다.
  Alpine.data('unsavedGuard', () => ({
    open: false,
    href: null,
    delta: null, // 뒤로/앞으로 가기에서 온 물음이면 이력 이동 폭(이동 확정 시 history.go(delta)), 아니면 null
    ask(href, delta) { this.href = href; this.delta = typeof delta === 'number' ? delta : null; this.open = true; },
    onAsk(e) { if (e.detail && e.detail.href) this.ask(e.detail.href, e.detail.delta); },
    onClick(e) {
      if (!Alpine.store('dirty').needsGuard() || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
      const a = e.target.closest && e.target.closest('a[href]');
      if (!a || a.target === '_blank' || a.hasAttribute('download')) return;
      const u = new URL(a.href, window.location.href);
      if (u.origin !== window.location.origin) return;
      if (u.pathname === window.location.pathname && u.search === window.location.search) return;
      e.preventDefault();
      this.ask(u.href);
    },
    stay() { this.open = false; this.href = null; this.delta = null; },
    leave() {
      const h = this.href;
      const d = this.delta;
      this.open = false;
      this.href = null;
      this.delta = null;
      Alpine.store('dirty').allow();
      if (d !== null) window.adminHistoryGo(d); // 보류했던 POP 을 진행(app.js 의 popstate 가 통과 허가를 소비하고 htmx 가 복원)
      else if (h) window.adminNavigate(h);
    },
  }));

  // 복구 코드 .txt 다운로드: 화면에 이미 그려진 코드를 브라우저에서 Blob 으로(서버 세션 저장 없음).
  window.adminDownloadText = (filename, text) => {
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };
});
