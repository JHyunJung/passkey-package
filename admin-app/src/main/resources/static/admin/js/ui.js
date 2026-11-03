// 공통 UI 조각의 Alpine 컴포넌트 (← admin-ui/src/ui/*.tsx). 템플릿: templates/admin/fragments/ui/*.html
// Alpine 보다 먼저 로드한다(alpine:init 에서 등록). 포커스 트랩은 @alpinejs/focus 의 x-trap 을 쓴다.
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

  /** 오류 응답의 HX-Trigger 토스트 메시지(WebExceptionAdvice 가 정한 쉬운 문구). 없으면 기본 문구. */
  function toastMessage(xhr) {
    try {
      const h = xhr && xhr.getResponseHeader && xhr.getResponseHeader('HX-Trigger');
      const m = h && JSON.parse(h).toast && JSON.parse(h).toast.message;
      if (m) return m;
    } catch (err) { /* 기본 문구 */ }
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
        if (v) { this.prev = document.activeElement; this.busy = false; this.typed = false; this.acked = false; this.error = null; }
        else if (this.prev && this.prev.focus) { const p = this.prev; this.prev = null; this.$nextTick(() => p.focus()); }
      });
      if (this.open) this.prev = document.activeElement;
    },
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
  // dirty 동안 새로고침·탭 닫기를 막는다.
  function formSnapshot(form) {
    const snap = {};
    new FormData(form).forEach((v, k) => { (snap[k] = snap[k] || []).push(String(v)); });
    return snap;
  }
  Alpine.data('saveBar', (cfg = {}) => ({
    form: null,
    base: {},
    count: 0,
    saveLabel: cfg.saveLabel || '저장',
    saving: false,
    disarmed: false,
    submitted: null,
    beforeUnload: null,
    disarm: null,
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
      // 기준선은 제출 시점의 값으로 잡는다: 저장 중에 더 고친 값은 성공 뒤에도 변경으로 남는다.
      f.addEventListener('htmx:before-request', (e) => {
        if (!f.contains(e.target)) return;
        this.saving = true;
        this.submitted = formSnapshot(f);
      });
      f.addEventListener('htmx:after-request', (e) => {
        if (!f.contains(e.target)) return;
        this.saving = false;
        if (e.detail && e.detail.successful && this.submitted) { this.base = this.submitted; this.recount(); }
        this.submitted = null;
      });
      // 일반(비 htmx) 제출은 페이지 이동이므로 경고를 끈다.
      f.addEventListener('submit', () => { if (!f.hasAttribute('hx-post') && !f.hasAttribute('hx-put') && !f.hasAttribute('hx-patch') && !f.hasAttribute('hx-delete')) this.disarmed = true; });
      this.beforeUnload = (e) => { if (this.count > 0 && !this.saving && !this.disarmed) { e.preventDefault(); e.returnValue = ''; } };
      this.disarm = () => { this.disarmed = true; };
      window.addEventListener('beforeunload', this.beforeUnload);
      window.addEventListener('unsaved-disarm', this.disarm); // unsavedGuard 가 '이동'을 확정했을 때
    },
    destroy() {
      if (this.beforeUnload) window.removeEventListener('beforeunload', this.beforeUnload);
      if (this.disarm) window.removeEventListener('unsaved-disarm', this.disarm);
    },
    recount() {
      const now = formSnapshot(this.form);
      const keys = new Set([...Object.keys(this.base), ...Object.keys(now)]);
      let n = 0;
      keys.forEach((k) => { if (JSON.stringify(this.base[k] || []) !== JSON.stringify(now[k] || [])) n += 1; });
      this.count = n;
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
  // dirty 일 때 같은 출처 링크 클릭을 가로채 "머무르기/이동" 확인을 띄운다(useUnsavedGuard).
  // dirty 는 `unsaved-change`({count}) 이벤트(saveBar 가 올림)로 받는다.
  Alpine.data('unsavedGuard', () => ({
    dirty: false,
    open: false,
    href: null,
    onChange(e) { this.dirty = (e.detail && e.detail.count > 0) || false; },
    onClick(e) {
      if (!this.dirty || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
      const a = e.target.closest && e.target.closest('a[href]');
      if (!a || a.target === '_blank' || a.hasAttribute('download')) return;
      const u = new URL(a.href, window.location.href);
      if (u.origin !== window.location.origin) return;
      if (u.pathname === window.location.pathname && u.search === window.location.search) return;
      e.preventDefault();
      this.href = u.href;
      this.open = true;
    },
    stay() { this.open = false; this.href = null; },
    // 이동을 확정하면 SaveBar 의 beforeunload 도 끈다(경고가 두 번 뜨지 않게).
    leave() {
      const h = this.href;
      this.dirty = false;
      this.open = false;
      window.dispatchEvent(new CustomEvent('unsaved-disarm'));
      if (h) window.location.assign(h);
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
