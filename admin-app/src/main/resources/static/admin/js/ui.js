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
  // 원격 검증이 통과하면서 응답 헤더 X-Normalized-Value 가 있으면 그 값(서버가 정규화한 값)을 추가한다.
  // cfg.added 가 배열이면 그 값들이 chip--added, null 이면 처음 값에 없던 값을 chip--added 로 센다.
  // cfg.meta(값 → 이름)·cfg.metaFallback 이 있으면 칩마다 메타(MDS 이름 등)를 보인다.
  // 같은 form 위의 `chip-add` 이벤트 {name, value}: 같은 name 의 칩 입력에 값을 더한다(별도 입력칸이 검증한 값을 넣을 때). 다른 form·화면에는 닿지 않는다.
  /** 서버 검증 GET. 반환 {error, value}: error 가 있으면 추가하지 않는다(검증 못 함 = 통과 아님). */
  async function remoteCheck(url, raw, existing) {
    try {
      const q = new URLSearchParams({ value: raw });
      existing.forEach((v) => q.append('existing', v));
      const res = await fetch(url + (url.includes('?') ? '&' : '?') + q, { headers: { 'HX-Request': 'true' }, credentials: 'same-origin' });
      if (!res.ok) return { error: '검증하지 못했습니다. 잠시 후 다시 시도하세요.', value: null };
      const text = (await res.text()).trim();
      return { error: text || null, value: res.headers.get('X-Normalized-Value') || null };
    } catch (e) {
      return { error: '검증하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도하세요.', value: null };
    }
  }
  Alpine.data('chipInput', (cfg = {}) => ({
    name: cfg.name || '',
    mono: !!cfg.mono,
    values: Array.isArray(cfg.values) ? [...cfg.values] : [],
    added: Array.isArray(cfg.added) ? cfg.added : [],
    meta: cfg.meta || {},
    metaFallback: cfg.metaFallback == null ? null : String(cfg.metaFallback),
    draft: '',
    error: null,
    disabled: !!cfg.disabled,
    validating: false,
    gen: 0, // 되돌리기마다 올려, 이미 보낸 검증 응답이 늦게 와도 버려진 초안을 추가하지 못하게 한다
    onExternal: null,
    extForm: null,
    init() {
      const initial = Array.isArray(cfg.values) ? [...cfg.values] : [];
      this.initial = initial;
      onFormReset(this.$root, () => { this.gen += 1; this.validating = false; this.values = [...initial]; this.draft = ''; this.error = null; this.refreshAdded(); this.changed(); });
      this.extForm = this.$root.closest ? this.$root.closest('form') : null;
      this.onExternal = (e) => { const d = e.detail || {}; if (d.name === this.name) this.addValue(d.value); };
      if (this.extForm) this.extForm.addEventListener('chip-add', this.onExternal);
    },
    destroy() { if (this.onExternal && this.extForm) this.extForm.removeEventListener('chip-add', this.onExternal); },
    refreshAdded() { if (cfg.added == null) this.added = this.values.filter((v) => !this.initial.includes(v)); },
    metaText(v) { return this.meta[v] || this.metaFallback || ''; },
    addValue(v) {
      if (!v || this.values.includes(v)) return;
      this.values.push(v);
      this.refreshAdded();
      this.changed();
    },
    async onEnter() {
      if (this.disabled || this.validating) return;
      const raw = this.draft.trim();
      if (!raw) return;
      let err = null;
      let norm = null;
      if (typeof cfg.validate === 'function') err = cfg.validate(raw, this.values) || null;
      else if (cfg.validateUrl) {
        // 검증을 못 하면 통과로 보지 않는다: 초안을 남기고 재시도 가능한 오류만 보인다.
        this.validating = true;
        const gen = this.gen;
        const r = await remoteCheck(cfg.validateUrl, raw, this.values);
        if (gen !== this.gen) return;
        this.validating = false;
        err = r.error;
        norm = r.value;
      }
      if (err) { this.error = err; return; }
      const v = norm || (typeof cfg.normalize === 'function' ? cfg.normalize(raw) : raw);
      this.error = null;
      this.draft = '';
      this.addValue(v);
    },
    remove(v) { this.values = this.values.filter((x) => x !== v); this.refreshAdded(); this.changed(); },
    changed() { this.$dispatch('chips-change', { values: [...this.values] }); },
    onInput() { if (this.error) this.error = null; },
  }));

  // 칩 입력과 별개의 입력칸(Android 앱 키 지문 등)이 서버에서 값을 만들어 같은 name 의 칩 입력에 넣는다.
  // cfg.url: GET ?value=…&existing=… → 오류 문구 본문(비면 통과) + X-Normalized-Value, cfg.target: 칩 입력의 name, cfg.existing: 같은 칩 입력의 값을 알아낼 form 필드 이름.
  Alpine.data('chipAdder', (cfg = {}) => ({
    input: '',
    error: null,
    busy: false,
    gen: 0, // 되돌리기·화면 이탈마다 올려, 늦게 온 검증 응답이 다른 상태/화면에 값을 넣지 못하게 한다
    form: null,
    init() {
      this.form = this.$root.closest ? this.$root.closest('form') : null;
      onFormReset(this.$root, () => { this.gen += 1; this.busy = false; this.input = ''; this.error = null; });
    },
    destroy() { this.gen += 1; },
    async add() {
      if (this.busy) return;
      const raw = this.input.trim();
      this.busy = true;
      this.error = null;
      const gen = this.gen;
      const form = this.form;
      const existing = form ? new FormData(form).getAll(cfg.target).map(String) : [];
      const r = await remoteCheck(cfg.url, raw, existing);
      if (gen !== this.gen) return;
      this.busy = false;
      if (r.error) { this.error = r.error; return; }
      this.input = '';
      if (r.value && form) form.dispatchEvent(new CustomEvent('chip-add', { detail: { name: cfg.target, value: r.value } }));
    },
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

  // ── API 키 탭(ApiKeysTab.tsx) ──────────────────────────
  // 새 키 발급 폼 상태: 이름·권한 범위·만료 프리셋. 다이얼로그(x-if) 바깥 요소에 두어 취소 후 다시 열어도 남고, 발급 성공 응답의 `ak-form-reset` 만 비운다.
  // 만료일 미리보기는 서버가 프리셋별로 계산해 넘긴다(previews: {"6": "YYYY-MM-DD", …}). months 는 문자열('' = 무기한).
  // 요청 중 잠금·닫기 차단은 안쪽 dialog 의 busy 를 쓰고(템플릿이 켜고 끈다), 여기 issuing 은 canSubmit 용이다.
  // 실패: 422 는 서버가 errorId 자리에 그린다. 스왑 없는 실패(권한 거부 등 4xx/5xx 의 confirmError, 네트워크 오류·시간 초과)는 netErr 로 다이얼로그 안에.
  const AK_DEFAULT_SCOPES = ['registration', 'authentication'];
  Alpine.data('apiKeyForm', (cfg = {}) => ({
    name: '',
    scopes: [...AK_DEFAULT_SCOPES],
    months: cfg.months == null ? '' : String(cfg.months),
    max: cfg.nameMax || 64,
    issuing: false,
    netErr: '',
    has(v) { return this.scopes.includes(v); },
    toggle(v) { this.scopes = this.has(v) ? this.scopes.filter((s) => s !== v) : [...this.scopes, v]; },
    get hint() {
      const p = this.months ? (cfg.previews || {})[this.months] : null;
      return (p ? `만료일 ${p} (KST)` : '만료 없음 — 키가 무기한으로 유효합니다.') + ` · 권장 회전 주기 ${cfg.rotationDays}일`;
    },
    get canSubmit() { return !this.issuing && this.name.length > 0 && this.scopes.length > 0; },
    failText(xhr) { return toastMessage(xhr).trim().replace(/[.。]$/, '') + '. 입력한 내용은 그대로 있습니다.'; },
    onBefore() {
      this.issuing = true;
      this.netErr = '';
      const e = document.getElementById(cfg.errorId || '');
      if (e) e.replaceChildren();
    },
    onAfter(e) {
      this.issuing = false;
      const d = (e && e.detail) || {};
      if (d.successful) return;
      const st = d.xhr ? d.xhr.status : 0;
      if (st === 422) return; // 서버가 errorId 자리에 그렸다
      this.netErr = this.failText(d.xhr);
    },
    // 전송 실패·시간 초과(afterRequest 와 겹쳐도 같은 문구): 응답이 없으므로 status 0 문구.
    netFail() { this.issuing = false; this.netErr = this.failText({ status: 0 }); },
    // 원본: 다이얼로그를 열 때 이전 오류만 지운다(입력은 유지).
    onDialogOpen(e) { if (e && e.detail && e.detail.id === 'ak-new') this.netErr = ''; },
    reset() { this.name = ''; this.scopes = [...AK_DEFAULT_SCOPES]; this.months = cfg.months == null ? '' : String(cfg.months); this.netErr = ''; this.issuing = false; },
  }));

  // 발급 완료 다이얼로그 안쪽(바깥 dialog 의 open 을 쓴다). 열려 있는 동안 $store.dirty 에 1건을 보고해 이동·새로고침을 막고(문구는 notice),
  // 체크 뒤 닫으면 자리(slot)를 비워 평문 키가 DOM(<template> 안 포함)에 남지 않게 한다.
  Alpine.data('apiKeyIssued', (cfg = {}) => ({
    checked: false,
    init() {
      const d = Alpine.store('dirty');
      d.notice = { title: cfg.leaveTitle || '', body: cfg.leaveBody || '' };
      d.report('apikey-issued', 1, false);
    },
    destroy() {
      const d = Alpine.store('dirty');
      d.report('apikey-issued', 0, false);
      d.notice = null;
    },
    finish() {
      if (!this.checked) return;
      this.open = false;
      const slot = document.getElementById(cfg.slot || '');
      this.$nextTick(() => { if (slot) slot.replaceChildren(); });
    },
  }));

  // ── 설정 › 내 계정(MFA) — AccountTab.tsx, RecoveryCodesModal.tsx ──
  // 세 컴포넌트 모두 바깥 dialog 범위 안(x-if)에 있다: open·busy 는 dialog 의 것이다(busy 는 템플릿의 x-effect 가 요청 상태로 비춘다).
  // 서버 실패(코드 불일치·권한 등)는 422 + HX-Retarget 로 입력칸 아래에 그려진다. 응답이 없는 실패(전송 실패·시간 초과)와 그 밖의 상태는 netErr.
  const MFA_NET = '네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도하세요.';
  const MFA_TIMEOUT = '서버가 잠시 응답하지 않습니다. 응답이 오지 않아 요청을 중단했습니다. 잠시 후 다시 시도하세요.';
  function mfaFailText(xhr) { return xhr && xhr.status ? toastMessage(xhr) : MFA_NET; }
  function clearById(id) { const s = document.getElementById(id); if (s) s.replaceChildren(); }
  function ownRequest(e, formId) { const elt = e && e.detail && e.detail.elt; return !!elt && elt.id === formId; }
  // 다이얼로그가 닫히면(x-if 가 안쪽을 지우면) 응답으로 받은 래퍼([data-mfa-wrap])째 지워 secret·복구 코드가 <template> 안에도 남지 않게 한다.
  function removeWrapLater(wrap) { setTimeout(() => { if (wrap && wrap.isConnected) wrap.remove(); }, 0); }

  // 켜기 1–2 / 3: 진행 표시(코드를 한 자리라도 넣으면 QR 스캔 완료 → 코드 확인 중), 6자리여야 확인, 요청 중 입력·취소·Escape 잠금.
  // 실패하면 코드를 비우고 첫 칸에 포커스(원본 focusKey). 다시 입력하면 오류가 사라진다.
  Alpine.data('mfaEnroll', () => ({
    code: '',
    confirming: false,
    netErr: '',
    wrap: null,
    init() { this.wrap = this.$root.closest('[data-mfa-wrap]'); },
    destroy() { removeWrapLater(this.wrap); },
    stepState(i) { const s = this.code.length > 0; if (i === 0) return s ? 'done' : 'now'; return s ? 'now' : 'todo'; },
    otp() { const f = this.$root.querySelector('.otp-set'); return f ? Alpine.$data(f) : null; },
    sync() {
      this.$nextTick(() => {
        const d = this.otp();
        const v = d ? d.value : '';
        if (v !== this.code) { this.code = v; this.clearErr(); }
      });
    },
    clearErr() { this.netErr = ''; clearById('mfa-enroll-error'); },
    cells(dis) { this.$root.querySelectorAll('.otp__cell').forEach((c) => { c.disabled = dis; }); },
    onBefore(e) { if (!ownRequest(e, 'mfa-confirm-form')) return; this.confirming = true; this.clearErr(); this.cells(true); },
    onAfter(e) {
      if (!ownRequest(e, 'mfa-confirm-form')) return;
      this.confirming = false;
      this.cells(false);
      const d = e.detail || {};
      if (d.successful) return;
      if (!(d.xhr && d.xhr.status === 422)) this.netErr = mfaFailText(d.xhr);
      this.resetCode();
    },
    onNetwork(timeout) { this.confirming = false; this.cells(false); this.netErr = timeout ? MFA_TIMEOUT : MFA_NET; this.resetCode(); },
    resetCode() { this.code = ''; const d = this.otp(); if (d) d.clear(); },
  }));

  // 끄기 확인창: 숫자 6자리만, 요청 중 읽기 전용(늦은 실패가 다시 친 값을 덮지 않음). 실패하면 값은 남기고 전체 선택 + 포커스 + aria-invalid.
  // 성공하면 응답이 계정 카드(#mfa-account)를 통째로 바꾼다(계정 정보 새로고침이 실패한 경우엔 카드는 그대로, 확인창만 닫는다).
  Alpine.data('mfaDisable', () => ({
    code: '',
    disabling: false,
    invalid: false,
    netErr: '',
    onInput(e) {
      if (this.disabling) { e.target.value = this.code; return; }
      this.code = (e.target.value || '').replace(/\D/g, '').slice(0, 6);
      e.target.value = this.code;
      if (this.invalid || this.netErr) this.clearErr();
    },
    clearErr() { this.invalid = false; this.netErr = ''; clearById('mfa-disable-err'); },
    onBefore(e) { if (!ownRequest(e, 'mfa-disable-form')) return; this.disabling = true; this.clearErr(); },
    onAfter(e) {
      if (!ownRequest(e, 'mfa-disable-form')) return;
      this.disabling = false;
      const d = e.detail || {};
      if (d.successful) { this.open = false; return; }
      if (!(d.xhr && d.xhr.status === 422)) this.netErr = mfaFailText(d.xhr);
      this.failed();
    },
    onNetwork(timeout) { this.disabling = false; this.netErr = timeout ? MFA_TIMEOUT : MFA_NET; this.failed(); },
    failed() {
      this.invalid = true;
      this.$nextTick(() => { const i = this.$refs.input; if (i) { i.focus(); i.select(); } });
    },
  }));

  // 복구 코드(3 / 3): 화면의 코드로 복사(줄바꿈으로 이음)·.txt 다운로드(Blob, 파일명은 서버가 RecoveryCodeFilename 으로 준 값).
  // "안전한 곳에 저장했습니다." 체크 뒤 닫기 버튼만 닫는다(Escape·스크림 무시: dialog dismissible=false). 열려 있는 동안 이동·새로고침을 막는다.
  Alpine.data('recoveryCodes', () => ({
    checked: false,
    copied: false,
    copyFailed: false,
    timer: null,
    wrap: null,
    init() {
      this.wrap = this.$root.closest('[data-mfa-wrap]');
      const d = Alpine.store('dirty');
      d.notice = { title: '복구 코드를 아직 저장하지 않았습니다', body: '이동하면 복구 코드를 다시 볼 수 없습니다.' };
      d.report('mfa-recovery-codes', 1, false);
    },
    destroy() {
      clearTimeout(this.timer);
      const d = Alpine.store('dirty');
      d.report('mfa-recovery-codes', 0, false);
      d.notice = null;
      removeWrapLater(this.wrap);
    },
    text() { return Array.from(this.$root.querySelectorAll('[data-rc-code]')).map((el) => el.textContent.trim()).join('\n'); },
    async copy() {
      const ok = await copyToClipboard(this.text());
      this.copyFailed = !ok;
      if (ok) {
        this.copied = true;
        clearTimeout(this.timer);
        this.timer = setTimeout(() => { this.copied = false; }, 2000);
      }
    },
    download() {
      const blob = new Blob([this.text() + '\n'], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.$root.dataset.filename || 'passkey-admin-recovery-codes.txt';
      a.click();
      URL.revokeObjectURL(url);
    },
    finish() { if (this.checked) this.open = false; },
  }));

  // ── SaveBar / 저장하지 않은 변경 ───────────────────────
  // form 의 필드 값을 처음 상태와 비교해 바뀐 개수를 센다. 되돌리기=form.reset(), 저장=form.requestSubmit().
  // 변경 개수와 저장 중 여부는 전역 Alpine.store('dirty')(app.js)에 보고한다. 새로고침·탭 닫기 경고(beforeunload)와 이동 확인은
  // 그 스토어를 보는 app.js 의 리스너 하나·unsavedGuard 하나가 맡는다(이 컴포넌트는 따로 경고하지 않는다).
  let saveBarSeq = 0;
  /** 비교용 값 목록: data-savebar-unordered 필드는 정렬한 집합으로(순서 무관), data-savebar-numeric 필드는 정수로(TSX parseInt). */
  function normField(form, k, arr) {
    const ds = form.dataset || {};
    const list = (x) => (x || '').split(/\s+/).filter(Boolean);
    let out = arr || [];
    if (list(ds.savebarUnordered).includes(k)) out = [...out].sort();
    if (list(ds.savebarNumeric).includes(k)) out = out.map((v) => { const n = parseInt(v || '0', 10); return String(Number.isFinite(n) ? n : 0); });
    return out;
  }
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
    // 저장 전 미리보기·확인 요청(data-preview)은 저장이 아니다: 성공해도 기준선을 바꾸지 않고 저장 중으로 세지 않는다.
    if (elt.hasAttribute && elt.hasAttribute('data-preview')) return false;
    if (elt === f) return true;
    return f.contains(elt) && !!elt.matches && elt.matches('button[type="submit"], input[type="submit"]');
  }

  Alpine.data('saveBar', (cfg = {}) => ({
    form: null,
    base: {},
    count: 0,
    labels: null, // form 의 data-savebar-labels(JSON {필드이름: 라벨}, 화면 순서): 바뀐 필드 이름 요약
    changedText: '',
    saveLabel: cfg.saveLabel || '저장',
    saving: false,
    previewing: false, // 저장 전 비교(data-preview) 요청 중: 입력을 잠그고 '저장 중…'
    uid: 'savebar-' + (++saveBarSeq),
    inflight: new Map(),
    pending: new Map(), // after-request 리스너 → 그 리스너를 단 출발 요소
    init() {
      this.form = cfg.form ? document.getElementById(cfg.form) : this.$root.closest('form');
      if (!this.form) return;
      const f = this.form;
      try { this.labels = f.dataset.savebarLabels ? JSON.parse(f.dataset.savebarLabels) : null; } catch (e) { this.labels = null; }
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
      // 저장 전 미리보기(data-preview) 요청 동안 form 을 잠그고(inert) 이전 필드 오류를 지운다. 요청이 끝나면(성공·실패 모두) 푼다.
      // 비교한 값과 다른 값이 이 사이에 입력돼 조용히 버려지는 일을 막는다(다이얼로그가 열리면 스크림이 막는다).
      f.addEventListener('htmx:before-request', (e) => {
        const elt = e.detail && e.detail.elt;
        if (elt !== f || !f.hasAttribute('data-preview')) return;
        f.querySelectorAll('[data-field-error]').forEach((p) => { p.textContent = ''; p.removeAttribute('role'); });
        this.previewing = true;
        f.setAttribute('inert', '');
        const done = (ev) => {
          if (!ev.detail || ev.detail.elt !== f) return;
          f.removeEventListener('htmx:after-request', done);
          this.previewing = false;
          f.removeAttribute('inert');
        };
        f.addEventListener('htmx:after-request', done);
        queueMicrotask(() => { if (e.defaultPrevented) done({ detail: { elt: f } }); });
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
      const changed = new Set();
      keys.forEach((k) => { if (JSON.stringify(normField(this.form, k, this.base[k])) !== JSON.stringify(normField(this.form, k, now[k]))) { n += 1; changed.add(k); } });
      this.count = n;
      this.changedText = this.labels ? Object.keys(this.labels).filter((k) => changed.has(k)).map((k) => this.labels[k]).join(' · ') : '';
      // [data-field] 행에 data-changed 를 달아 "변경됨" 표시를 CSS 가 켠다.
      this.form.querySelectorAll('[data-field]').forEach((el) => { el.toggleAttribute('data-changed', changed.has(el.dataset.field)); });
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
      onFormReset(this.$root, () => { this.value = initial; this.$dispatch('segmented-change', { value: initial }); });
    },
    select(v) {
      if (v === this.value) return;
      this.value = v;
      this.$dispatch('segmented-change', { value: v });
    },
  }));

  // ── WebAuthn 설정 탭: 대기 시간 안내(WebauthnConfigTab.tsx timeoutGuide) ──
  // 입력이 바뀔 때마다 환산·권장 범위 안내를 다시 계산한다(서버가 처음 값을 같은 문구로 그린다). form reset 이면 처음 값으로.
  function timeoutGuide(raw) {
    const ms = parseInt(raw || '0', 10);
    if (!Number.isFinite(ms) || ms <= 0) return { tone: 'warn', text: '0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).' };
    const totalSec = ms / 1000;
    const secLabel = Number.isInteger(totalSec) ? totalSec + '초' : parseFloat(totalSec.toFixed(2)) + '초';
    let human = '= ' + secLabel;
    if (totalSec >= 60) {
      const m = Math.floor(totalSec / 60);
      const s = Math.round(totalSec - m * 60);
      human = '= ' + secLabel + (s === 0 ? ' (' + m + '분)' : ' (' + m + '분 ' + s + '초)');
    }
    if (ms < 60000) return { tone: 'warn', text: human + ' — 권장(60초)보다 짧습니다. 너무 짧으면 사용자가 인증을 끝내기 전에 만료될 수 있습니다.' };
    if (ms > 120000) return { tone: 'warn', text: human + ' — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 서버 자원이 오래 점유됩니다.' };
    return { tone: 'ok', text: human + ' — 권장 범위(60–120초) 안입니다.' };
  }
  Alpine.data('timeoutHint', (cfg = {}) => ({
    ms: cfg.value == null ? '' : String(cfg.value),
    init() {
      const initial = this.ms;
      onFormReset(this.$root, () => { this.ms = initial; });
    },
    get guide() { return timeoutGuide(this.ms); },
  }));

  // 선택(Segmented)에 따라 설명 상자를 바꾼다: 안쪽 segmented 의 `segmented-change`(reset 포함)를 받아 v 를 따라간다.
  Alpine.data('guideSwitch', (cfg = {}) => ({
    v: cfg.value == null ? '' : String(cfg.value),
    on(e) { this.v = e.detail && e.detail.value != null ? String(e.detail.value) : this.v; },
  }));

  // ── AAGUID 정책 탭: 방식·목록·MDS 엄격의 화면 상태 ──
  // 방식 선택 카드(setMode)가 hidden input(name=mode)을 바꾸고 `change` 를 올려 saveBar 가 변경을 센다. 목록(chips-change)과
  // MDS 엄격(switch-change)은 자식 컴포넌트가 올리는 이벤트로 따라간다(form reset 도 그 컴포넌트가 이벤트로 알린다).
  // cfg: mode, strict, values(처음 목록), known(MDS 이름이 있는 AAGUID), impact·noteBefore·noteAfter(안내 문구 조각).
  Alpine.data('policyForm', (cfg = {}) => ({
    mode: cfg.mode || 'ANY',
    strict: !!cfg.strict,
    values: Array.isArray(cfg.values) ? [...cfg.values] : [],
    init() {
      const initial = this.mode;
      onFormReset(this.$root, () => { this.mode = initial; });
    },
    setMode(v) {
      if (!v || v === this.mode) return;
      this.mode = v;
      this.$nextTick(() => this.$dispatch('change'));
    },
    get count() { return this.values.length; },
    get unknown() { const k = cfg.known || []; return this.values.filter((v) => !k.includes(v)).length; },
    get strictHint() {
      const n = this.mode === 'ALLOWLIST' ? this.unknown : 0;
      return (this.strict ? '' : '켜면 ') + (cfg.impact || '') + (n > 0 ? (cfg.noteBefore || '') + n + (cfg.noteAfter || '') : '');
    },
    focusInput() { const el = document.getElementById(cfg.inputId || ''); if (el) el.focus(); },
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

  // ── 설정 › Admin 사용자 ──
  // 운영자 목록 필터(원본 matchesOperator): 역할 선택(segmented-change 가 filter 를 바꾼다)과 이메일·ID 검색을 이미 그려진 행에 적용한다.
  Alpine.data('operatorFilter', () => ({
    filter: 'all',
    q: '',
    match(el) {
      const d = el.dataset;
      if (this.filter === 'PLATFORM' && d.role !== 'PLATFORM_OPERATOR') return false;
      if (this.filter === 'RP_ADMIN' && d.role !== 'RP_ADMIN') return false;
      if (this.filter === 'SUSPENDED' && d.status !== 'SUSPENDED') return false;
      const q = this.q.trim().toLowerCase();
      return q === '' || (d.hay || '').includes(q);
    },
    get none() { return Array.from(this.$root.querySelectorAll('tr[data-op]')).every((r) => !this.match(r)); },
  }));

  // 가입 요청 승인 대화상자(원본 ApproveDialog): 역할 선택, RP Admin 이면 tenant 1개 이상(없으면 제출 막고 오류), 검색, 요청 중 닫기·중복 제출 막기.
  // 서버가 같은 규칙을 다시 검증한다(422 + #au-approve-err). 요청 중에는 바깥 dialog 의 busy 를 비춰 Escape·스크림·취소로 닫히지 않게 한다.
  Alpine.data('signupApprove', () => ({
    role: 'RP_ADMIN',
    tenantIds: [],
    q: '',
    touched: false,
    submitting: false,
    get formValid() { return this.role === 'PLATFORM_OPERATOR' || this.tenantIds.length > 0; },
    get showErr() { return this.role === 'RP_ADMIN' && this.touched && this.tenantIds.length === 0; },
    matches(hay) { const q = this.q.trim().toLowerCase(); return q === '' || (hay || '').includes(q); },
    get noMatch() {
      const rows = Array.from(this.$root.querySelectorAll('[data-tenant]'));
      return rows.length > 0 && rows.every((r) => !this.matches(r.dataset.q));
    },
    onSubmit(e) { this.touched = true; if (!this.formValid || this.submitting) e.preventDefault(); },
    onBefore(e) { if (!ownRequest(e, 'au-approve-form')) return; this.submitting = true; clearById('au-approve-err'); },
    onAfter(e) { if (ownRequest(e, 'au-approve-form')) this.submitting = false; },
  }));

  // ── 설정 › 보안 정책 ──
  // idle timeout 즉시 검증(원본 timeoutError: 손대지 않았으면 오류 아님), 콘솔 주소 제거 경고·삭제 되돌리기(원본 removesConsole / restoreConsoleOrigin).
  // 칩 목록의 변경은 chips-change 로 받는다. 되돌리기는 삭제한 주소를 chip-add 로 다시 넣는다(저장 바는 이 목록을 순서 없이 비교하므로 변경 0건이 된다).
  const SEC_TIMEOUT_ERROR = '1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.';
  const SEC_RESTORE_FULL = '목록이 가득 찼습니다(최대 64개). 다른 origin 을 지운 뒤 되돌리세요.';
  Alpine.data('securityPolicy', (cfg = {}) => ({
    idle: cfg.idle == null ? '' : String(cfg.idle),
    cors: Array.isArray(cfg.cors) ? [...cfg.cors] : [],
    listError: '',
    init() {
      const idle0 = this.idle;
      const cors0 = [...this.cors];
      onFormReset(this.$root, () => { this.idle = idle0; this.cors = [...cors0]; this.listError = ''; });
    },
    get timeoutErr() {
      const v = this.idle.trim();
      if (v === String(cfg.idle == null ? '' : cfg.idle)) return '';
      if (!/^\d+$/.test(v)) return SEC_TIMEOUT_ERROR;
      const n = parseInt(v, 10);
      return n >= 1 && n <= 1440 ? '' : SEC_TIMEOUT_ERROR;
    },
    has(list) { const c = (cfg.consoleOrigin || '').toLowerCase(); return list.some((o) => String(o).toLowerCase() === c); },
    get removesConsole() { return !!cfg.savedHasConsole && !this.has(this.cors); },
    onChips(e) { this.listError = ''; this.cors = (e.detail && e.detail.values) || []; },
    restore() {
      if (this.cors.length >= 64) { this.listError = SEC_RESTORE_FULL; return; }
      this.listError = '';
      this.$root.dispatchEvent(new CustomEvent('chip-add', { detail: { name: 'corsAllowlist', value: cfg.consoleSaved } }));
    },
    onSubmit(e) {
      if (this.timeoutErr) { e.preventDefault(); e.stopPropagation(); const i = this.$refs.idle; if (i) i.focus(); }
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
