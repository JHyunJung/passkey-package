// admin-ui/src/pages/settings/AdminUsersTab.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { adminUsersApi, type AdminUserView } from '@/api/adminUsers';
import { signupRequestsApi, type SignupRequestView } from '@/api/signupRequests';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import {
  Badge, Button, ChoiceCard, DataTable, Dialog, EmptyState, Segmented, StatusBadge, useToast, type Column,
} from '@/ui';

// ── Local utilities ───────────────────────────────────────────────────────────

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

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function initial(email: string): string {
  return email.slice(0, 1).toUpperCase();
}

type OperatorFilter = 'all' | 'PLATFORM' | 'RP_ADMIN' | 'SUSPENDED';
const FILTERS: { value: OperatorFilter; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'PLATFORM', label: 'PLATFORM' },
  { value: 'RP_ADMIN', label: 'RP_ADMIN' },
  { value: 'SUSPENDED', label: '정지' },
];

function matchesOperator(u: AdminUserView, filter: OperatorFilter, q: string): boolean {
  if (filter === 'PLATFORM' && u.role !== 'PLATFORM_OPERATOR') return false;
  if (filter === 'RP_ADMIN' && u.role !== 'RP_ADMIN') return false;
  if (filter === 'SUSPENDED' && u.status !== 'SUSPENDED') return false;
  return q === '' || u.email.toLowerCase().includes(q) || u.id.toLowerCase().includes(q);
}

const WARN_AVATAR = { width: 34, height: 34, background: 'var(--warn-soft)', color: 'var(--warn)' } as const;
const CAPTION = { margin: 0 } as const;

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab({ onPendingCountChange }: { onPendingCountChange?: (n: number) => void } = {}) {
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [requests, setRequests] = useState<SignupRequestView[]>([]);
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState<SignupRequestView | null>(null);
  const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);
  const [filter, setFilter] = useState<OperatorFilter>('all');
  const [query, setQuery] = useState('');
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const [list, pending] = await Promise.all([adminUsersApi.list(), signupRequestsApi.list()]);
      setUsers(list);
      setRequests(pending);
      onPendingCountChange?.(pending.length);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'Admin 사용자 로드 실패', message: errMsg(e) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleApprove(req: SignupRequestView, body: { role: string; tenantIds: string[] }) {
    try {
      await signupRequestsApi.approve(req.id, body);
      setApproving(null);
      toast({ kind: 'ok', title: '가입 요청을 승인했습니다.', message: `${req.email} · 즉시 로그인 가능` });
      await reload();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setApproving(null);
        await reload();
      } else {
        toast({ kind: 'err', title: '승인 실패', message: errMsg(e) });
      }
    }
  }

  async function handleReject(req: SignupRequestView) {
    try {
      await signupRequestsApi.reject(req.id);
      toast({ kind: 'warn', title: '가입 요청을 거절했습니다.', message: req.email });
      setRejecting(null);
      await reload();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        toast({ kind: 'warn', title: '이미 처리된 요청입니다.', message: '다른 관리자가 먼저 처리했습니다.' });
        setRejecting(null);
        await reload();
      } else {
        toast({ kind: 'err', title: '거절 실패', message: errMsg(e) });
      }
    }
  }

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUsersApi.suspend(u.id);
      toast({ kind: 'warn', title: '운영자가 정지되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: errMsg(e) });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자가 재활성화되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '활성화 실패', message: errMsg(e) });
    }
  }

  if (loading && users.length === 0 && requests.length === 0) {
    return (
      <section className="card">
        <div className="card__body muted" style={{ fontSize: 13 }}>Loading…</div>
      </section>
    );
  }

  const activeCount = users.filter((u) => u.status === 'ACTIVE').length;
  const q = query.trim().toLowerCase();
  const visible = users.filter((u) => matchesOperator(u, filter, q));

  const columns: Column<AdminUserView>[] = [
    {
      key: 'who', header: '운영자',
      render: (u) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <span className="avatar" style={{ width: 30, height: 30, fontSize: 12 }} aria-hidden="true">{initial(u.email)}</span>
          <div className="col" style={{ minWidth: 0 }}>
            <strong style={{ fontSize: 13 }}>{u.email}</strong>
            <span className="hint mono" style={CAPTION}>…{tail(u.id, 10)}</span>
          </div>
        </div>
      ),
    },
    {
      key: 'role', header: '역할',
      render: (u) => (
        <Badge tone={u.role === 'PLATFORM_OPERATOR' ? 'plat' : 'info'} dot={false}>
          {u.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'RP_ADMIN'}
        </Badge>
      ),
    },
    {
      key: 'tenant', header: 'Tenant',
      render: (u) => {
        const ids = u.tenantIds ?? [];
        if (ids.length === 0) return <span className="faint">—</span>;
        if (ids.length === 1) return <span className="mono" style={{ fontSize: 12 }} title={ids[0]}>…{tail(ids[0], 10)}</span>;
        return <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ids.length}개 RP</span>;
      },
    },
    { key: 'mfa', header: 'MFA', render: (u) => <Badge tone={u.mfaEnabled ? 'ok' : 'warn'}>{u.mfaEnabled ? 'ON' : 'OFF'}</Badge> },
    {
      key: 'last', header: '마지막 로그인',
      render: (u) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{u.lastLoginAt ? timeAgo(u.lastLoginAt) : '미접속'}</span>,
    },
    { key: 'status', header: '상태', render: (u) => <StatusBadge status={u.status} /> },
    {
      key: 'act', header: '액션',
      render: (u) =>
        u.status === 'ACTIVE' ? <Button size="sm" variant="danger" onClick={() => void handleSuspend(u)}>정지</Button>
        : u.status === 'SUSPENDED' ? <Button size="sm" onClick={() => void handleActivate(u)}>활성화</Button>
        : null,
    },
  ];

  return (
    <div className="stack-6">
      {/* ── 가입 요청 ── */}
      {requests.length === 0 ? (
        <section className="card" aria-label="가입 요청">
          <div className="card__head" style={{ borderBottom: 0 }}>
            <h2 className="card__title">가입 요청</h2>
            <Badge>대기 0건</Badge>
            <div className="spacer" />
            <span className="hint" style={CAPTION}>대기 중인 요청이 없습니다</span>
          </div>
        </section>
      ) : (
        <section className="card" aria-label="가입 요청" style={{ borderColor: 'var(--warn-line)' }}>
          <div className="card__head" style={{ background: 'var(--warn-soft)', borderRadius: 'var(--r) var(--r) 0 0' }}>
            <div className="col" style={{ gap: 2 }}>
              <div className="row" style={{ gap: 8 }}>
                <h2 className="card__title">가입 요청</h2>
                <Badge tone="warn">대기 {requests.length}건</Badge>
              </div>
              <p className="card__sub">로그인 화면에서 들어온 관리자 계정 요청. 승인 시 역할과 tenant 를 지정합니다.</p>
            </div>
          </div>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {requests.map((r, i) => (
              <li key={r.id} className="row" style={{ gap: 14, padding: '14px 20px', borderBottom: i < requests.length - 1 ? '1px solid var(--line-soft)' : 0 }}>
                <span className="avatar" style={WARN_AVATAR} aria-hidden="true">{initial(r.email)}</span>
                <div className="col" style={{ flex: '1 1 260px', minWidth: 0 }}>
                  <strong style={{ fontSize: 13 }}>{r.email}</strong>
                  <span className="truncate" style={{ fontSize: 13, color: 'var(--ink-2)' }} title={r.reason ?? undefined}>
                    {r.reason ?? '요청 사유 없음'}
                  </span>
                </div>
                <span className="hint" style={CAPTION}>{timeAgo(r.requestedAt)}</span>
                <div className="row" style={{ gap: 6 }}>
                  <Button onClick={() => setRejecting(r)}>거절</Button>
                  <Button variant="primary" onClick={() => setApproving(r)}>승인…</Button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── 콘솔 운영자 ── */}
      <section className="card">
        <div className="card__head">
          <div className="col" style={{ gap: 2 }}>
            <h2 className="card__title">콘솔 운영자</h2>
            <span className="hint" style={CAPTION}>{users.length}명 · 활성 {activeCount}명</span>
          </div>
          <div className="spacer" />
          <Segmented ariaLabel="역할" options={FILTERS} value={filter} onChange={setFilter} />
          <div className="search" style={{ flex: '0 1 220px' }}>
            <Icons.Search size={15} />
            <input className="input" placeholder="이메일 · ID" aria-label="운영자 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={visible}
          rowKey={(u) => u.id}
          empty={<EmptyState compact illustration={illustrations.noResults} title="조건에 맞는 운영자가 없습니다." />}
        />
      </section>

      {approving && (
        <ApproveDialog
          request={approving}
          onClose={() => setApproving(null)}
          onApprove={(body) => handleApprove(approving, body)}
        />
      )}

      {rejecting && (
        <Dialog
          open
          onClose={() => setRejecting(null)}
          role="alertdialog"
          title="가입 요청 거절"
          footer={
            <>
              <Button onClick={() => setRejecting(null)}>취소</Button>
              <Button variant="danger-solid" onClick={() => void handleReject(rejecting)}>거절 확정</Button>
            </>
          }
        >
          <p className="card__sub">{rejecting.email} 의 요청을 거절합니다. 요청자는 다시 가입 요청을 보낼 수 있습니다.</p>
        </Dialog>
      )}
    </div>
  );
}

// ── ApproveDialog ─────────────────────────────────────────────────────────────

function ApproveDialog({
  request,
  onClose,
  onApprove,
}: {
  request: SignupRequestView;
  onClose: () => void;
  onApprove: (body: { role: string; tenantIds: string[] }) => Promise<void>;
}) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [role, setRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN'>('RP_ADMIN');
  const [tenantIds, setTenantIds] = useState<string[]>([]);
  const [tenantQuery, setTenantQuery] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    tenantsApi
      .list()
      .then((list) => {
        // 임의 tenant 를 미리 선택하지 않는다 — 운영자가 접근 권한을 줄 tenant 를 직접 고른다.
        setTenants(list.filter((t) => t.status === 'ACTIVE'));
      })
      .catch(() => { /* non-critical */ });
  }, []);

  function toggleTenant(id: string) {
    setTouched(true);
    setTenantIds((prev) => (prev.includes(id) ? prev.filter((t) => t !== id) : [...prev, id]));
  }

  const formValid = role === 'PLATFORM_OPERATOR' || tenantIds.length > 0;
  const showTenantError = role === 'RP_ADMIN' && touched && tenantIds.length === 0;
  const tq = tenantQuery.trim().toLowerCase();
  const shown = tq ? tenants.filter((t) => t.name.toLowerCase().includes(tq) || t.slug.toLowerCase().includes(tq)) : tenants;

  async function submit() {
    setTouched(true);
    if (!formValid) return;
    setSubmitting(true);
    try {
      await onApprove({ role, tenantIds: role === 'RP_ADMIN' ? tenantIds : [] });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="가입 요청 승인"
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={submitting} onClick={() => void submit()}>승인하고 계정 생성</Button>
        </>
      }
    >
      <div className="row" style={{ gap: 12, flexWrap: 'nowrap', padding: '12px 14px', borderRadius: 'var(--r)', background: 'var(--canvas)' }}>
        <span className="avatar" style={WARN_AVATAR} aria-hidden="true">{initial(request.email)}</span>
        <div className="col" style={{ minWidth: 0 }}>
          <strong style={{ fontSize: 13 }}>{request.email}</strong>
          <span style={{ fontSize: 13, color: 'var(--ink-2)', whiteSpace: 'pre-wrap' }}>{request.reason ?? '요청 사유 없음'}</span>
        </div>
      </div>

      <div>
        <span className="label">역할</span>
        <div className="choices choices--2" role="radiogroup" aria-label="역할">
          <ChoiceCard checked={role === 'PLATFORM_OPERATOR'} onSelect={() => setRole('PLATFORM_OPERATOR')} title="Platform Operator" description="모든 tenant 와 플랫폼 설정 관리" />
          <ChoiceCard checked={role === 'RP_ADMIN'} onSelect={() => setRole('RP_ADMIN')} title="RP Admin" description="할당된 tenant 만 관리" />
        </div>
      </div>

      {role === 'RP_ADMIN' && (
        <div>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">할당 Tenant</span>
            <span className="hint" style={{ margin: '0 0 6px' }}>{tenantIds.length}개 선택</span>
          </div>
          <div className="search" style={{ marginBottom: 8 }}>
            <Icons.Search size={15} />
            <input className="input" placeholder="tenant 검색" aria-label="tenant 검색" value={tenantQuery} onChange={(e) => setTenantQuery(e.target.value)} />
          </div>
          <div
            role="group"
            aria-label="할당 Tenant"
            style={{ border: `1px solid ${showTenantError ? 'var(--bad)' : 'var(--line)'}`, borderRadius: 'var(--r)', maxHeight: 176, overflow: 'auto' }}
          >
            {tenants.length === 0 ? (
              <p className="hint" style={{ margin: 0, padding: '10px 14px' }}>활성 tenant 없음</p>
            ) : shown.length === 0 ? (
              <p className="hint" style={{ margin: 0, padding: '10px 14px' }}>일치하는 tenant 없음</p>
            ) : (
              shown.map((t, i) => {
                const checked = tenantIds.includes(t.id);
                return (
                  <label
                    key={t.id}
                    className="row"
                    style={{
                      gap: 10, flexWrap: 'nowrap', padding: '10px 14px', fontSize: 13, cursor: 'pointer',
                      borderBottom: i < shown.length - 1 ? '1px solid var(--line-soft)' : 0,
                      background: checked ? 'var(--brand-50)' : undefined,
                    }}
                  >
                    <input type="checkbox" checked={checked} onChange={() => toggleTenant(t.id)} style={{ width: 16, height: 16, margin: 0, accentColor: 'var(--brand-700)' }} />
                    <span className="truncate">{t.name}</span>
                    <span className="spacer" />
                    <span className="mono hint" style={{ margin: 0 }}>{t.slug}</span>
                  </label>
                );
              })
            )}
          </div>
          {showTenantError && <p className="field__error" role="alert">Tenant를 최소 1개 선택해야 합니다.</p>}
        </div>
      )}
    </Dialog>
  );
}
