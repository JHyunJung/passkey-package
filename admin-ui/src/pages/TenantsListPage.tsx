import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { useToast } from '@/shell/ToastHost';
import { StatusBadge } from '@/shell/StatusBadge';
import { Dialog } from '@/shell/Dialog';
import { downloadCsv } from '@/lib/csvExport';

// ── local utilities (mirrors design globals) ─────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

// ── TenantsListPage ───────────────────────────────────────────────────────────

export default function TenantsListPage() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'SUSPENDED'>('ALL');
  const navigate = useNavigate();
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await tenantsApi.list();
      setTenants(list);
    } catch (e: unknown) {
      const err = e as { message?: string };
      toast({ kind: 'err', title: 'tenant 목록 로드 실패', message: err?.message });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  async function handleCreate(input: { name: string; slug: string; rpId: string }) {
    try {
      const t = await tenantsApi.create(input);
      toast({ kind: 'ok', title: 'Tenant 생성 완료', message: t.name });
      setShowNew(false);
      await reload();
    } catch (e: unknown) {
      const err = e as { message?: string };
      toast({ kind: 'err', title: '생성 실패', message: err?.message || '오류' });
    }
  }

  const filtered = useMemo(
    () =>
      tenants.filter((t) => {
        const matchesQ =
          !q ||
          t.name.toLowerCase().includes(q.toLowerCase()) ||
          t.slug.includes(q.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || t.status === statusFilter;
        return matchesQ && matchesStatus;
      }),
    [q, statusFilter, tenants],
  );

  const totalCredentials = useMemo(
    () => tenants.reduce((a, t) => a + t.credentials, 0),
    [tenants],
  );
  const totalKeys = useMemo(
    () => tenants.reduce((a, t) => a + t.apiKeys, 0),
    [tenants],
  );
  const totalActive = useMemo(
    () => tenants.filter((t) => t.status === 'ACTIVE').length,
    [tenants],
  );

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Tenants</h1>
          <div className="page__sub">RP 회사별 격리된 Passkey 환경. 모든 데이터는 tenant_id로 row-level 분리되어 있습니다.</div>
        </div>
        {/* '신규 tenant'(POST /admin/api/tenants)는 PLATFORM_OPERATOR 전용. 이 페이지(/tenants)
            자체가 App.tsx 의 RequirePlatform 으로 감싸져 있어 RP_ADMIN 은 진입 시 자기 테넌트로
            redirect 된다 — 따라서 여기서 별도 버튼 게이팅은 불필요(가드가 단일 진입점). */}
        <button className="btn btn--primary" onClick={() => setShowNew(true)}>
          <Icons.Plus size={14} /> 신규 tenant
        </button>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard label="활성 Tenant" value={totalActive} sub={`전체 ${tenants.length}건`} />
        <MetricCard label="등록 Credential" value={fmt(totalCredentials)} sub="모든 tenant 합산" />
        <MetricCard label="유효 API Key" value={totalKeys} sub="ACTIVE 상태만" />
        <MetricCard label="24h ceremony" value="2.4M" sub="평균 응답 18ms" />
      </div>

      <div className="card">
        <div className="card__head" style={{ gap: 8 }}>
          <div className="row" style={{ gap: 10 }}>
            <div style={{ position: 'relative', width: 280 }}>
              <span style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-mute)' }}><Icons.Search size={13} /></span>
              <input className="input" placeholder="name · slug 검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ paddingLeft: 28, height: 30 }} />
            </div>
            <button
              className={`btn btn--sm ${statusFilter !== 'ALL' ? 'btn--active' : ''}`}
              onClick={() =>
                setStatusFilter((s) => (s === 'ALL' ? 'ACTIVE' : s === 'ACTIVE' ? 'SUSPENDED' : 'ALL'))
              }
            >
              <Icons.Filter size={12} /> {statusFilter === 'ALL' ? '필터' : statusFilter}
            </button>
            <span className="muted" style={{ fontSize: 12 }}>{filtered.length} / {tenants.length}건</span>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button
              className="btn btn--sm"
              onClick={() =>
                downloadCsv(
                  `tenants-${new Date().toISOString().slice(0, 10)}.csv`,
                  ['name', 'slug', 'rpId', 'status', 'credentials', 'apiKeys', 'createdAt'],
                  filtered.map((t) => [t.name, t.slug, t.rpId, t.status, t.credentials, t.apiKeys, t.createdAt]),
                )
              }
            >
              <Icons.Download size={12} /> CSV
            </button>
          </div>
        </div>

        {loading ? (
          <div style={{ padding: '32px 16px', textAlign: 'center', color: 'var(--text-mute)' }}>로딩 중…</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Tenant</th>
                <th>Slug</th>
                <th>RP ID</th>
                <th style={{ textAlign: 'right' }}>Credentials</th>
                <th style={{ textAlign: 'right' }}>API Keys</th>
                <th>Status</th>
                <th>마지막 이벤트</th>
                <th>생성일</th>
                <th style={{ width: 40 }}></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((t) => (
                <tr
                  key={t.id}
                  onClick={() => onOpen(t.id)}
                  onKeyDown={(ev) => {
                    if (ev.key === 'Enter' || ev.key === ' ') {
                      ev.preventDefault();
                      onOpen(t.id);
                    }
                  }}
                  role="button"
                  tabIndex={0}
                  style={{ cursor: 'pointer' }}
                >
                  <td>
                    <div className="row">
                      <div style={{ width: 26, height: 26, borderRadius: 6, background: 'var(--accent-soft)', color: 'var(--accent)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 11, flex: 'none' }}>
                        {t.name.slice(0, 1)}
                      </div>
                      <div className="stack-1">
                        <div style={{ fontWeight: 600 }}>{t.name}</div>
                        <div className="mono muted" style={{ fontSize: 11 }}>{tail(t.id, 8)}</div>
                      </div>
                    </div>
                  </td>
                  <td className="mono">{t.slug}</td>
                  <td><span className="mono" style={{ fontSize: 12 }}>{t.rpId}</span></td>
                  <td style={{ textAlign: 'right' }} className="mono">{fmt(t.credentials)}</td>
                  <td style={{ textAlign: 'right' }} className="mono">{t.apiKeys}</td>
                  <td><StatusBadge status={t.status} /></td>
                  <td><span className="muted" style={{ fontSize: 12 }}>{timeAgo(t.lastEventAt)}</span></td>
                  <td><span className="muted" style={{ fontSize: 12 }}>{fmtDateTime(t.createdAt)}</span></td>
                  <td><button className="btn btn--ghost btn--xs" onClick={(e) => { e.stopPropagation(); onOpen(t.id); }}><Icons.ChevronRight size={14} /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div style={{ padding: '10px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12, color: 'var(--text-mute)', borderTop: '1px solid var(--border)' }}>
          <span>page 1 of 1 · {filtered.length}건</span>
          <div className="row" style={{ gap: 4 }}>
            <button className="btn btn--xs" disabled><Icons.ChevronLeft size={12} /></button>
            <button className="btn btn--xs" disabled><Icons.ChevronRight size={12} /></button>
          </div>
        </div>
      </div>

      {showNew && (
        <NewTenantDialog
          open={showNew}
          onClose={() => setShowNew(false)}
          onCreate={handleCreate}
        />
      )}
    </div>
  );
}

// ── MetricCard ────────────────────────────────────────────────────────────────

function MetricCard({ label, value, sub, delta }: {
  label: string;
  value: string | number;
  sub?: string;
  delta?: number;
}) {
  return (
    <div className="card" style={{ padding: 16 }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-delta">{sub}{delta !== undefined && <span style={{ color: delta > 0 ? 'var(--success)' : 'var(--danger)', marginLeft: 6 }}>{delta > 0 ? '▲' : '▼'} {Math.abs(delta)}%</span>}</div>
    </div>
  );
}

// ── NewTenantDialog ───────────────────────────────────────────────────────────

function NewTenantDialog({ open, onClose, onCreate }: {
  open: boolean;
  onClose: () => void;
  onCreate: (i: { name: string; slug: string; rpId: string }) => void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [rpId, setRpId] = useState('');
  const [rpIdEdited, setRpIdEdited] = useState(false);
  const [touched, setTouched] = useState(false);
  const slugRe = /^[a-z][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이
  // 점이 포함된 호스트명이어야 한다(예: passkey.acme.com). placeholder 그대로 저장 방지.
  const rpIdRe = /^(?!.*\.example\.com$)([a-z0-9-]+\.)+[a-z]{2,}$/;
  const rpIdOk = rpIdRe.test(rpId.trim());

  // Reset form when dialog is closed (open: true → false) so stale input
  // doesn't bleed into the next open. This also runs when a create fails and
  // the parent leaves the dialog open — state is preserved until actual close.
  useEffect(() => {
    if (!open) { setName(''); setSlug(''); setRpId(''); setRpIdEdited(false); setTouched(false); }
  }, [open]);

  function generate(n: string) {
    const s = n.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
    setSlug(s);
    // rpId 를 사용자가 아직 직접 수정하지 않았다면 slug 기반 제안값을 채운다(수정 가능).
    if (!rpIdEdited) setRpId(s ? `${s}.crosscert.com` : '');
  }

  function submit() {
    setTouched(true);
    if (!name || !slugOk || !rpIdOk) return;
    onCreate({ name, slug, rpId: rpId.trim() });
    // Do NOT clear here — let the parent's handleCreate success path call
    // setShowNew(false), which triggers the useEffect above to reset state.
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="신규 Tenant 생성"
      sub="새 RP를 온보딩합니다. 생성 후 WebAuthn 설정과 API key 발급으로 이어집니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={submit}>생성하고 설정으로 이동</button>
      </>}
    >
      <div className="stack-3">
        <div>
          <label className="label">표시 이름</label>
          <input className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => { setName(e.target.value); if (!slug) generate(e.target.value); }} />
        </div>
        <div>
          <label className="label">Slug (영문 식별자)</label>
          <input className="input mono" placeholder="acme-corp" value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())} />
          <div className="hint">
            {touched && slug && !slugOk
              ? <span style={{ color: 'var(--danger)' }}>영문 소문자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.</span>
              : <>테넌트를 구분하는 영문 식별자입니다. 영문 소문자로 시작하고, 영문 소문자·숫자·하이픈(-)만 쓸 수 있습니다(2~63자). 띄어쓰기·대문자·한글은 사용할 수 없습니다. <strong>생성 후에는 변경할 수 없습니다.</strong></>}
          </div>
        </div>
        <div>
          <label className="label">rpId (Relying Party ID)</label>
          <input className="input mono" placeholder="예: passkey.acme.com" value={rpId}
                 onChange={(e) => { setRpId(e.target.value.toLowerCase().trim()); setRpIdEdited(true); }} />
          <div className="hint">
            {touched && rpId && !rpIdOk
              ? <span style={{ color: 'var(--danger)' }}>스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.</span>
              : <span style={{ color: 'var(--danger)' }}>생성 후에는 변경할 수 없습니다. 패스키가 묶일 실제 RP 서버 도메인을 정확히 입력하세요.</span>}
          </div>
        </div>

        <div style={{ padding: 12, background: 'var(--surface-3)', borderRadius: 8, fontSize: 12, color: 'var(--text-soft)' }}>
          <div style={{ fontWeight: 600, marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
            <Icons.Info size={13} /> 다음 단계
          </div>
          <ol style={{ margin: 0, paddingLeft: 18, lineHeight: 1.8 }}>
            <li>WebAuthn config — origins, UV 정책 설정(rpId는 위에서 확정)</li>
            <li>AAGUID 정책 — 시작은 <code>ANY</code> 권장, 이후 ALLOWLIST로 좁힘</li>
            <li>API key 발급 — plaintext는 1회만 노출됩니다</li>
          </ol>
        </div>
      </div>
    </Dialog>
  );
}
