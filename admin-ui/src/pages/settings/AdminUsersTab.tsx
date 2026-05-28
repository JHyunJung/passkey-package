import { useEffect, useState, type FormEvent } from 'react';
import { adminUserApi } from '../../api/adminUser';
import { api } from '../../api/client';
import { ApiError } from '../../api/types';
import type {
  AdminUserView,
  AdminUserStatus,
  InviteRequest,
  InvitationInfo,
  TenantView,
} from '../../api/types';
import { useToast } from '../../components/Toast';
import Dialog from '../../components/Dialog';
import { useMe } from '../../me/MeContext';
import { formatDateTime } from '../../lib/formatDateTime';
import { Copy, Alert, Plus } from '../../components/Icons';

// ── Helpers ─────────────────────────────────────────────────────────────────

function statusBadge(s: AdminUserStatus) {
  const map: Record<AdminUserStatus, string> = {
    ACTIVE: 'badge--success',
    PENDING: 'badge--warning',
    SUSPENDED: 'badge--danger',
  };
  const label: Record<AdminUserStatus, string> = {
    ACTIVE: 'Active',
    PENDING: 'Pending',
    SUSPENDED: 'Suspended',
  };
  return <span className={`badge ${map[s]} badge--dot`}>{label[s]}</span>;
}

function roleLabel(r: AdminUserView['role']) {
  return r === 'PLATFORM_OPERATOR' ? 'Platform Operator' : 'RP Admin';
}

// ── InviteLinkModal (plaintext 1회 노출) ────────────────────────────────────

interface InviteLinkModalProps {
  invitation: InvitationInfo;
  email: string;
  onClose: () => void;
}

function InviteLinkModal({ invitation, email, onClose }: InviteLinkModalProps) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);
  const [confirmed, setConfirmed] = useState(false);

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(invitation.acceptUrl);
      setCopied(true);
      toast({ kind: 'ok', title: '클립보드에 복사됨' });
    } catch {
      toast({ kind: 'err', title: '복사 실패', message: '수동으로 링크를 복사하세요.' });
    }
  }

  const canClose = confirmed;

  return (
    <Dialog
      open
      onClose={canClose ? onClose : () => {}}
      closeOnScrim={false}
      title="초대 링크 발급 완료"
      sub={`${email} 계정에 초대 링크가 생성되었습니다. 이 링크는 지금 한 번만 표시됩니다.`}
      wide
      footer={
        <>
          <button className="btn btn--outline" onClick={copyLink}>
            <Copy size={14} /> 링크 복사
          </button>
          <button
            className="btn btn--primary"
            onClick={onClose}
            disabled={!confirmed}
          >
            {confirmed ? '완료' : '전달 완료를 확인해주세요'}
          </button>
        </>
      }
    >
      <div className="banner banner--warning" style={{ marginBottom: 16 }}>
        <Alert size={16} className="banner__icon" />
        <div>
          <div className="banner__title">한 번만 노출되는 초대 링크</div>
          <div className="banner__body">
            창을 닫으면 이 URL을 다시 볼 수 없습니다. 반드시 담당자에게 전달하세요.
          </div>
        </div>
      </div>

      <div className="stack-4">
        <div>
          <div className="label">ACCEPT URL</div>
          <code
            className="mono"
            style={{
              display: 'block',
              padding: 12,
              background: 'var(--surface-sunk)',
              borderRadius: 'var(--radius)',
              wordBreak: 'break-all',
              fontSize: 12,
              marginTop: 4,
            }}
          >
            {invitation.acceptUrl}
          </code>
        </div>

        <div className="row" style={{ gap: 8, fontSize: 13 }}>
          <span className="muted" style={{ minWidth: 80 }}>만료 시각</span>
          <span style={{ fontWeight: 500 }}>{formatDateTime(invitation.expiresAt)}</span>
        </div>

        {copied && (
          <label
            className="row"
            style={{ gap: 8, cursor: 'pointer', fontSize: 13 }}
          >
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            담당자에게 링크를 전달 완료했습니다
          </label>
        )}

        {!copied && (
          <div className="muted" style={{ fontSize: 12 }}>
            위 링크를 복사한 후 전달 완료 체크박스가 표시됩니다.
          </div>
        )}
      </div>
    </Dialog>
  );
}

// ── InviteDialog (신규 운영자 초대 폼) ─────────────────────────────────────

interface InviteDialogProps {
  onClose: () => void;
  onInvited: (result: { user: AdminUserView; invitation: InvitationInfo }) => void;
}

function InviteDialog({ onClose, onInvited }: InviteDialogProps) {
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [emailErr, setEmailErr] = useState<string | null>(null);
  const [role, setRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN'>('PLATFORM_OPERATOR');
  const [tenantId, setTenantId] = useState('');
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants').then(setTenants).catch(() => {});
  }, []);

  function validateEmail(v: string) {
    if (!v) { setEmailErr('이메일을 입력하세요.'); return false; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) {
      setEmailErr('올바른 이메일 형식이 아닙니다.');
      return false;
    }
    setEmailErr(null);
    return true;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!validateEmail(email)) return;
    if (role === 'RP_ADMIN' && !tenantId) {
      toast({ kind: 'err', title: '초대 불가', message: 'RP Admin은 tenant를 선택해야 합니다.' });
      return;
    }
    setBusy(true);
    try {
      const body: InviteRequest = {
        email,
        role,
        ...(role === 'RP_ADMIN' ? { tenantId } : {}),
      };
      const resp = await adminUserApi.invite(body);
      onInvited(resp);
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({
        kind: 'err',
        title: '초대 실패',
        message: e?.serverMessage ?? String(err),
        traceId: e?.traceId,
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="운영자 초대"
      sub="초대 링크가 포함된 이메일을 보내거나 링크를 직접 전달합니다."
      footer={
        <>
          <button className="btn btn--outline" onClick={onClose}>취소</button>
          <button form="invite-form" type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? '초대 중…' : '초대 링크 생성'}
          </button>
        </>
      }
    >
      <form id="invite-form" onSubmit={submit} className="stack-4">
        {/* Email */}
        <div>
          <label className="label" htmlFor="invite-email">이메일</label>
          <input
            id="invite-email"
            type="email"
            className="input"
            value={email}
            onChange={(e) => { setEmail(e.target.value); if (emailErr) validateEmail(e.target.value); }}
            onBlur={() => validateEmail(email)}
            required
            placeholder="admin@company.com"
            autoFocus
          />
          {emailErr && <div className="hint" style={{ color: 'var(--danger)', marginTop: 4 }}>{emailErr}</div>}
        </div>

        {/* Role cards */}
        <div>
          <div className="label" style={{ marginBottom: 8 }}>역할</div>
          <div className="row" style={{ gap: 10 }}>
            {(['PLATFORM_OPERATOR', 'RP_ADMIN'] as const).map((r) => (
              <label
                key={r}
                style={{
                  flex: 1,
                  border: `2px solid ${role === r ? 'var(--accent)' : 'var(--border-subtle)'}`,
                  borderRadius: 'var(--radius)',
                  padding: '12px 14px',
                  cursor: 'pointer',
                  background: role === r ? 'var(--accent-soft)' : 'var(--surface-2)',
                  transition: 'all 0.15s',
                }}
              >
                <input
                  type="radio"
                  name="invite-role"
                  value={r}
                  checked={role === r}
                  onChange={() => { setRole(r); setTenantId(''); }}
                  style={{ display: 'none' }}
                />
                <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 3 }}>
                  {r === 'PLATFORM_OPERATOR' ? 'Platform Operator' : 'RP Admin'}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-mute)' }}>
                  {r === 'PLATFORM_OPERATOR'
                    ? '전체 tenant 관리 권한'
                    : '특정 tenant 전용 관리자'}
                </div>
              </label>
            ))}
          </div>
        </div>

        {/* Tenant select — only for RP_ADMIN */}
        {role === 'RP_ADMIN' && (
          <div>
            <label className="label" htmlFor="invite-tenant">Tenant</label>
            <select
              id="invite-tenant"
              className="input"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              required
            >
              <option value="">테넌트 선택…</option>
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName ?? t.slug} ({t.slug})
                </option>
              ))}
            </select>
          </div>
        )}
      </form>
    </Dialog>
  );
}

// ── ResendModal (재발송 결과 확인) ───────────────────────────────────────────

interface ResendModalProps {
  invitation: InvitationInfo;
  email: string;
  onClose: () => void;
}

function ResendModal({ invitation, email, onClose }: ResendModalProps) {
  return (
    <InviteLinkModal invitation={invitation} email={email} onClose={onClose} />
  );
}

// ── Main Component ───────────────────────────────────────────────────────────

export default function AdminUsersTab() {
  const { me } = useMe();
  const toast = useToast();
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [loading, setLoading] = useState(true);

  // Dialog states
  const [showInvite, setShowInvite] = useState(false);
  const [pendingInvitation, setPendingInvitation] = useState<{ invitation: InvitationInfo; email: string } | null>(null);
  const [resendResult, setResendResult] = useState<{ invitation: InvitationInfo; email: string } | null>(null);

  function load() {
    setLoading(true);
    adminUserApi
      .list()
      .then((data) => { setUsers(data); setLoading(false); })
      .catch((err: unknown) => {
        setLoading(false);
        const e = err instanceof ApiError ? err : null;
        toast({
          kind: 'err',
          title: '운영자 목록을 가져오지 못했습니다',
          message: e?.serverMessage,
          traceId: e?.traceId,
        });
      });
  }

  useEffect(load, []);

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUserApi.suspend(u.id);
      toast({ kind: 'ok', title: `${u.email} 정지 완료` });
      load();
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '정지 실패', message: e?.serverMessage, traceId: e?.traceId });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUserApi.activate(u.id);
      toast({ kind: 'ok', title: `${u.email} 활성화 완료` });
      load();
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '활성화 실패', message: e?.serverMessage, traceId: e?.traceId });
    }
  }

  async function handleResend(u: AdminUserView) {
    try {
      const inv = await adminUserApi.resend(u.id, u.email);
      setResendResult({ invitation: inv, email: u.email });
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '재발송 실패', message: e?.serverMessage, traceId: e?.traceId });
    }
  }

  function isSelf(u: AdminUserView) {
    return me?.email === u.email;
  }

  return (
    <div className="stack-5">
      {/* Header */}
      <div className="page__head">
        <div>
          <h2 className="page__title" style={{ fontSize: 16 }}>Admin Users</h2>
          <div className="page__sub">플랫폼 운영자 및 RP Admin 계정 관리.</div>
        </div>
        <button className="btn btn--primary" onClick={() => setShowInvite(true)}>
          <Plus size={14} /> 운영자 추가
        </button>
      </div>

      {/* Table */}
      <div className="card">
        {loading ? (
          <div className="card__body">
            <div className="stack-2">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="skeleton" style={{ height: 36 }} />
              ))}
            </div>
          </div>
        ) : users.length === 0 ? (
          <div className="empty">
            <div className="empty__title">등록된 운영자 없음</div>
            <div className="empty__sub">운영자 추가 버튼으로 초대를 시작하세요.</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>EMAIL</th>
                <th>ROLE</th>
                <th>STATUS</th>
                <th>TENANT</th>
                <th>CREATED</th>
                <th>LAST LOGIN</th>
                <th>ACTIONS</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>
                    <span style={{ fontWeight: 500 }}>{u.email}</span>
                    {isSelf(u) && (
                      <span
                        className="badge badge--info"
                        style={{ marginLeft: 6, fontSize: 10 }}
                      >
                        나
                      </span>
                    )}
                  </td>
                  <td>{roleLabel(u.role)}</td>
                  <td>{statusBadge(u.status)}</td>
                  <td className="mono muted">{u.tenantId ?? '—'}</td>
                  <td className="mono muted">{formatDateTime(u.createdAt)}</td>
                  <td className="mono muted">{formatDateTime(u.lastLoginAt)}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      {u.status === 'PENDING' && (
                        <>
                          <button
                            className="btn btn--outline btn--sm"
                            disabled={isSelf(u)}
                            onClick={() => handleResend(u)}
                          >
                            재발송
                          </button>
                        </>
                      )}
                      {u.status === 'ACTIVE' && (
                        <button
                          className="btn btn--outline btn--sm"
                          disabled={isSelf(u)}
                          onClick={() => handleSuspend(u)}
                        >
                          정지
                        </button>
                      )}
                      {u.status === 'SUSPENDED' && (
                        <button
                          className="btn btn--outline btn--sm"
                          disabled={isSelf(u)}
                          onClick={() => handleActivate(u)}
                        >
                          활성화
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Invite Dialog */}
      {showInvite && (
        <InviteDialog
          onClose={() => setShowInvite(false)}
          onInvited={(result) => {
            setShowInvite(false);
            setPendingInvitation({ invitation: result.invitation, email: result.user.email });
            load();
          }}
        />
      )}

      {/* Invite Link Modal (plaintext 1회 노출) */}
      {pendingInvitation && (
        <InviteLinkModal
          invitation={pendingInvitation.invitation}
          email={pendingInvitation.email}
          onClose={() => setPendingInvitation(null)}
        />
      )}

      {/* Resend Result Modal */}
      {resendResult && (
        <ResendModal
          invitation={resendResult.invitation}
          email={resendResult.email}
          onClose={() => setResendResult(null)}
        />
      )}
    </div>
  );
}
