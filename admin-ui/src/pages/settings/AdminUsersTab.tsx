import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { adminUsersApi, type AdminUserView } from '@/api/adminUsers';
import { signupRequestsApi, type SignupRequestView } from '@/api/signupRequests';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';
import { useToast } from '@/shell/ToastHost';
import { StatusBadge } from '@/shell/StatusBadge';
import { Dialog } from '@/shell/Dialog';

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

function Field({ label, hint, children }: { label: string; hint?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab() {
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [requests, setRequests] = useState<SignupRequestView[]>([]);
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState<SignupRequestView | null>(null);
  const [rejecting, setRejecting] = useState<SignupRequestView | null>(null);
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const [list, pending] = await Promise.all([adminUsersApi.list(), signupRequestsApi.list()]);
      setUsers(list);
      setRequests(pending);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'Admin 사용자 로드 실패', message: errMsg(e) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

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
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  const activeCount = users.filter((u) => u.status === 'ACTIVE').length;

  return (
    <div className="stack-4">
      {/* ── 가입 요청 ── */}
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">가입 요청</h3>
            <div className="card__sub">로그인 화면에서 접수된 계정 요청. 승인 시 역할과 tenant 를 지정합니다.</div>
          </div>
          <span className={`badge ${requests.length > 0 ? 'badge--warning' : ''}`}>대기 {requests.length}건</span>
        </div>
        {requests.length === 0 ? (
          <div style={{ padding: '14px 16px', fontSize: 13, color: 'var(--text-mute)' }}>대기 중인 요청이 없습니다</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>이메일</th>
                <th>요청 사유</th>
                <th>요청 시각</th>
                <th style={{ textAlign: 'right' }}>액션</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((r) => (
                <tr key={r.id}>
                  <td style={{ fontWeight: 600, fontSize: 13 }}>{r.email}</td>
                  <td style={{ fontSize: 13, color: r.reason ? 'var(--text)' : 'var(--text-mute)' }}>{r.reason ?? '—'}</td>
                  <td><span className="muted">{timeAgo(r.requestedAt)}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                      <button className="btn btn--primary btn--xs" onClick={() => setApproving(r)}>승인</button>
                      <button className="btn btn--xs" style={{ color: 'var(--danger)' }} onClick={() => setRejecting(r)}>거절</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* ── 콘솔 운영자 ── */}
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">콘솔 운영자</h3>
            <div className="card__sub">{users.length}명 · 활성 {activeCount}명</div>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>운영자</th>
              <th>역할</th>
              <th>Tenant</th>
              <th>MFA</th>
              <th>마지막 로그인</th>
              <th>상태</th>
              <th style={{ textAlign: 'right' }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} style={{ opacity: u.status === 'SUSPENDED' ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <div style={{
                      width: 28, height: 28, borderRadius: 999,
                      background: u.role === 'PLATFORM_OPERATOR' ? 'var(--violet-soft)' : 'var(--info-soft)',
                      color: u.role === 'PLATFORM_OPERATOR' ? 'var(--violet)' : 'var(--info)',
                      display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 11, flex: 'none',
                    }}>
                      {u.email.slice(0, 1).toUpperCase()}
                    </div>
                    <div className="stack-1">
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{u.email}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>{tail(u.id, 10)}</div>
                    </div>
                  </div>
                </td>
                <td>
                  <span className={`badge ${u.role === 'PLATFORM_OPERATOR' ? 'badge--violet' : 'badge--info'}`}>{u.role}</span>
                </td>
                <td>
                  {u.tenantIds && u.tenantIds.length > 0 ? (
                    <span className="mono" style={{ fontSize: 12 }}>
                      {u.tenantIds.length === 1 ? tail(u.tenantIds[0], 10) : `${u.tenantIds.length}개 RP`}
                    </span>
                  ) : (
                    <span className="faint">—</span>
                  )}
                </td>
                <td>
                  {u.mfaEnabled ? (
                    <span className="badge badge--success" style={{ fontSize: 10 }}><Icons.Check size={10} /> ON</span>
                  ) : (
                    <span className="badge badge--warning" style={{ fontSize: 10 }}><Icons.Alert size={10} /> OFF</span>
                  )}
                </td>
                <td>
                  {u.lastLoginAt ? <span className="muted">{timeAgo(u.lastLoginAt)}</span> : <span className="faint">미접속</span>}
                </td>
                <td><StatusBadge status={u.status} /></td>
                <td style={{ textAlign: 'right' }}>
                  <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                    {u.status === 'ACTIVE' && (
                      <button className="btn btn--xs" onClick={() => void handleSuspend(u)} style={{ color: 'var(--warning)' }}>정지</button>
                    )}
                    {u.status === 'SUSPENDED' && (
                      <button className="btn btn--xs" onClick={() => void handleActivate(u)} style={{ color: 'var(--success)' }}>활성화</button>
                    )}
                    <button className="btn btn--ghost btn--xs"><Icons.Dots size={14} /></button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
          title="가입 요청 거절"
          sub={`${rejecting.email} 의 요청을 거절합니다. 요청은 삭제되며 같은 이메일로 다시 요청할 수 있습니다.`}
          footer={
            <>
              <button className="btn" onClick={() => setRejecting(null)}>취소</button>
              <button className="btn btn--danger" onClick={() => void handleReject(rejecting)}>거절 확정</button>
            </>
          }
        >
          <div style={{ fontSize: 13, color: 'var(--text-mute)' }}>거절 사실은 요청자에게 메일로 안내되고 audit log 에 기록됩니다.</div>
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
  const [role, setRole] = useState('RP_ADMIN');
  const [tenantIds, setTenantIds] = useState<string[]>([]);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    tenantsApi
      .list()
      .then((list) => {
        const active = list.filter((t) => t.status === 'ACTIVE');
        setTenants(active);
        if (active.length > 0) setTenantIds([active[0].id]);
      })
      .catch(() => { /* non-critical */ });
  }, []);

  function toggleTenant(id: string) {
    setTenantIds((prev) => (prev.includes(id) ? prev.filter((t) => t !== id) : [...prev, id]));
  }

  const formValid = role === 'PLATFORM_OPERATOR' || tenantIds.length > 0;

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
      wide
      title="가입 요청 승인"
      sub="역할과 tenant 를 지정하면 계정이 즉시 활성화되고 요청자가 정한 비밀번호로 로그인할 수 있습니다."
      footer={
        <>
          <button className="btn" onClick={onClose}>취소</button>
          <button className="btn btn--primary" disabled={!formValid || submitting} onClick={() => void submit()}>
            승인하고 계정 생성
          </button>
        </>
      }
    >
      <div className="stack-3">
        <Field label="이메일" hint="요청자가 입력한 로그인 ID 입니다.">
          <input className="input" type="email" value={request.email} readOnly />
        </Field>

        {request.reason && (
          <Field label="요청 사유">
            <div style={{ padding: '8px 12px', background: 'var(--surface-3)', borderRadius: 6, fontSize: 13, whiteSpace: 'pre-wrap' }}>{request.reason}</div>
          </Field>
        )}

        <Field label="Role">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            {[
              { v: 'PLATFORM_OPERATOR', t: 'Platform Operator', d: '모든 tenant에 대해 cross-tenant 운영 가능. Crosscert 사내용.' },
              { v: 'RP_ADMIN', t: 'RP Admin', d: '한 tenant 안에서만 모든 권한. RP 회사의 IAM 담당자용.' },
            ].map((opt) => (
              <button key={opt.v} type="button" onClick={() => setRole(opt.v)} style={{
                padding: '10px 12px', borderRadius: 8,
                border: `1px solid ${role === opt.v ? 'var(--accent)' : 'var(--border)'}`,
                background: role === opt.v ? 'var(--accent-soft)' : 'var(--surface)',
                color: role === opt.v ? 'var(--accent)' : 'var(--text)', cursor: 'pointer', textAlign: 'left',
              }}>
                <div className="row" style={{ gap: 6 }}>
                  <Icons.Shield size={13} />
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{opt.t}</div>
                </div>
                <div style={{ fontSize: 11, marginTop: 4, lineHeight: 1.5,
                  color: role === opt.v ? 'color-mix(in oklab, var(--accent) 70%, var(--text))' : 'var(--text-mute)' }}>
                  {opt.d}
                </div>
              </button>
            ))}
          </div>
        </Field>

        {role === 'RP_ADMIN' && (
          <Field
            label="할당 Tenant"
            hint={tenantIds.length === 0 && touched
              ? <span style={{ color: 'var(--danger)' }}>Tenant를 최소 1개 선택해야 합니다.</span>
              : '이 운영자가 접근 가능한 tenant를 선택하세요 (복수 선택 가능).'}
          >
            <div style={{ border: '1px solid var(--border)', borderRadius: 6, overflow: 'hidden', maxHeight: 200, overflowY: 'auto' }}>
              {tenants.length === 0 && (
                <div style={{ padding: '10px 12px', fontSize: 13, color: 'var(--text-mute)' }}>활성 tenant 없음</div>
              )}
              {tenants.map((t, i) => {
                const checked = tenantIds.includes(t.id);
                return (
                  <label key={t.id} style={{
                    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
                    borderBottom: i < tenants.length - 1 ? '1px solid var(--border)' : 'none',
                    background: checked ? 'var(--accent-soft)' : 'transparent', cursor: 'pointer', fontSize: 13,
                  }}>
                    <input type="checkbox" checked={checked} onChange={() => toggleTenant(t.id)} />
                    <div style={{ flex: 1 }}>
                      <div style={{ fontWeight: 600, color: checked ? 'var(--accent)' : 'var(--text)' }}>{t.name}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>{t.slug}</div>
                    </div>
                  </label>
                );
              })}
            </div>
          </Field>
        )}
      </div>
    </Dialog>
  );
}
