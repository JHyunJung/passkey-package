import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { adminUsersApi, type AdminUserView, type InvitationInfo } from '@/api/adminUsers';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { getMfa } from '@/fixtures/adminMfa';
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

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// ── AdminUsersTab ─────────────────────────────────────────────────────────────

export default function AdminUsersTab() {
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const [invitation, setInvitation] = useState<InvitationInfo | null>(null);
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await adminUsersApi.list();
      setUsers(list);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'Admin 사용자 로드 실패', message: msg });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  async function handleCreate(body: { email: string; role: string; tenantId?: string }) {
    try {
      const res = await adminUsersApi.invite(body);
      setShowNew(false);
      setInvitation(res.invitation);
      await reload();
      toast({
        kind: 'ok',
        title: '운영자가 생성되었습니다.',
        message: `${res.user.email} · 초대 링크 생성 완료`,
      });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '운영자 생성 실패', message: msg });
    }
  }

  async function handleSuspend(u: AdminUserView) {
    try {
      await adminUsersApi.suspend(u.id);
      toast({ kind: 'warn', title: '운영자가 정지되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '정지 실패', message: msg });
    }
  }

  async function handleActivate(u: AdminUserView) {
    try {
      await adminUsersApi.activate(u.id);
      toast({ kind: 'ok', title: '운영자가 재활성화되었습니다.', message: u.email });
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '활성화 실패', message: msg });
    }
  }

  async function handleResend(u: AdminUserView) {
    try {
      const info = await adminUsersApi.resendInvitation(u.id, u.email);
      setInvitation(info);
      toast({ kind: 'ok', title: '초대가 재발송되었습니다.', message: u.email });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '재발송 실패', message: msg });
    }
  }

  if (loading && users.length === 0) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  const activeCount = users.filter((u) => u.status === 'ACTIVE').length;
  const pendingCount = users.filter((u) => u.status === 'PENDING').length;

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">콘솔 운영자</h3>
            <div className="card__sub">
              {users.length}명 · 활성 {activeCount}명 · 대기 {pendingCount}명
            </div>
          </div>
          <button className="btn btn--primary btn--sm" onClick={() => setShowNew(true)}>
            <Icons.Plus size={12} /> 운영자 추가
          </button>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>운영자</th>
              <th>Role</th>
              <th>Tenant</th>
              <th>MFA</th>
              <th>마지막 로그인</th>
              <th>Status</th>
              <th style={{ textAlign: 'right' }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} style={{ opacity: u.status === 'SUSPENDED' ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <div
                      style={{
                        width: 28,
                        height: 28,
                        borderRadius: 999,
                        background:
                          u.role === 'PLATFORM_OPERATOR'
                            ? 'var(--violet-soft)'
                            : 'var(--info-soft)',
                        color:
                          u.role === 'PLATFORM_OPERATOR' ? 'var(--violet)' : 'var(--info)',
                        display: 'grid',
                        placeItems: 'center',
                        fontWeight: 700,
                        fontSize: 11,
                        flex: 'none',
                      }}
                    >
                      {u.email.slice(0, 1).toUpperCase()}
                    </div>
                    <div className="stack-1">
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{u.email}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>
                        {tail(u.id, 10)}
                      </div>
                    </div>
                  </div>
                </td>
                <td>
                  <span
                    className={`badge ${u.role === 'PLATFORM_OPERATOR' ? 'badge--violet' : 'badge--info'}`}
                  >
                    {u.role}
                  </span>
                </td>
                <td>
                  {u.tenantId ? (
                    <span className="mono" style={{ fontSize: 12 }}>
                      {tail(u.tenantId, 10)}
                    </span>
                  ) : (
                    <span className="faint">—</span>
                  )}
                </td>
                <td>
                  {getMfa(u.id) ? (
                    <span className="badge badge--success" style={{ fontSize: 10 }}>
                      <Icons.Check size={10} /> ON
                    </span>
                  ) : (
                    <span className="badge badge--warning" style={{ fontSize: 10 }}>
                      <Icons.Alert size={10} /> OFF
                    </span>
                  )}
                </td>
                <td>
                  {u.lastLoginAt ? (
                    <span className="muted">{timeAgo(u.lastLoginAt)}</span>
                  ) : (
                    <span className="faint">미접속</span>
                  )}
                </td>
                <td>
                  <StatusBadge status={u.status} />
                </td>
                <td style={{ textAlign: 'right' }}>
                  <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                    {u.status === 'PENDING' && (
                      <button
                        className="btn btn--xs"
                        title="초대 재발송"
                        onClick={() => void handleResend(u)}
                      >
                        <Icons.Refresh size={11} />
                      </button>
                    )}
                    {u.status === 'ACTIVE' && (
                      <button
                        className="btn btn--xs"
                        onClick={() => void handleSuspend(u)}
                        style={{ color: 'var(--warning)' }}
                      >
                        정지
                      </button>
                    )}
                    {u.status === 'SUSPENDED' && (
                      <button
                        className="btn btn--xs"
                        onClick={() => void handleActivate(u)}
                        style={{ color: 'var(--success)' }}
                      >
                        활성화
                      </button>
                    )}
                    <button className="btn btn--ghost btn--xs">
                      <Icons.Dots size={14} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showNew && (
        <NewAdminDialog
          open={showNew}
          onClose={() => setShowNew(false)}
          onCreate={handleCreate}
          existingEmails={users.map((u) => u.email)}
        />
      )}

      {invitation && (
        <InvitationModal info={invitation} onClose={() => setInvitation(null)} />
      )}
    </div>
  );
}

// ── NewAdminDialog ─────────────────────────────────────────────────────────────

function NewAdminDialog({
  open,
  onClose,
  onCreate,
  existingEmails,
}: {
  open: boolean;
  onClose: () => void;
  onCreate: (body: { email: string; role: string; tenantId?: string }) => Promise<void>;
  existingEmails: string[];
}) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('RP_ADMIN');
  const [tenantId, setTenantId] = useState('');
  const [requireMfa, setRequireMfa] = useState(true);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    tenantsApi
      .list()
      .then((list) => {
        const active = list.filter((t) => t.status === 'ACTIVE');
        setTenants(active);
        if (active.length > 0 && !tenantId) setTenantId(active[0].id);
      })
      .catch(() => { /* non-critical */ });
  }, []);

  useEffect(() => {
    if (!open) {
      setEmail('');
      setRole('RP_ADMIN');
      setTenantId(tenants[0]?.id || '');
      setRequireMfa(true);
      setTouched(false);
      setSubmitting(false);
    }
  }, [open]);

  const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const emailValid = emailRe.test(email);
  const emailDup = existingEmails.map((e) => e.toLowerCase()).includes(email.toLowerCase());
  const formValid =
    emailValid && !emailDup && (role === 'PLATFORM_OPERATOR' || !!tenantId);

  async function submit() {
    setTouched(true);
    if (!formValid) return;
    setSubmitting(true);
    try {
      await onCreate({
        email: email.toLowerCase(),
        role,
        ...(role === 'RP_ADMIN' && tenantId ? { tenantId } : {}),
      });
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;
  const selectedTenant = tenants.find((t) => t.id === tenantId);

  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title="운영자 추가"
      sub="새 운영자에게 초대 링크가 생성됩니다. 24시간 안에 비밀번호를 설정하지 않으면 만료됩니다."
      footer={
        <>
          <button className="btn" onClick={onClose}>
            취소
          </button>
          <button
            className="btn btn--primary"
            disabled={!formValid || submitting}
            onClick={() => void submit()}
          >
            운영자 생성 + 초대 발송
          </button>
        </>
      }
    >
      <div className="stack-3">
        <Field
          label="이메일"
          hint={
            touched && emailDup ? (
              <span style={{ color: 'var(--danger)' }}>이미 등록된 이메일입니다</span>
            ) : touched && email && !emailValid ? (
              <span style={{ color: 'var(--danger)' }}>유효한 이메일 형식이 아닙니다</span>
            ) : (
              '로그인 ID로 사용됩니다.'
            )
          }
        >
          <input
            className="input"
            type="email"
            placeholder="user@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoFocus
          />
        </Field>

        <Field label="Role">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            {[
              {
                v: 'PLATFORM_OPERATOR',
                t: 'Platform Operator',
                d: '모든 tenant에 대해 cross-tenant 운영 가능. Crosscert 사내용.',
              },
              {
                v: 'RP_ADMIN',
                t: 'RP Admin',
                d: '한 tenant 안에서만 모든 권한. RP 회사의 IAM 담당자용.',
              },
            ].map((opt) => (
              <button
                key={opt.v}
                type="button"
                onClick={() => setRole(opt.v)}
                style={{
                  padding: '10px 12px',
                  borderRadius: 8,
                  border: `1px solid ${role === opt.v ? 'var(--accent)' : 'var(--border)'}`,
                  background: role === opt.v ? 'var(--accent-soft)' : 'var(--surface)',
                  color: role === opt.v ? 'var(--accent)' : 'var(--text)',
                  cursor: 'pointer',
                  textAlign: 'left',
                }}
              >
                <div className="row" style={{ gap: 6 }}>
                  <Icons.Shield size={13} />
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{opt.t}</div>
                </div>
                <div
                  style={{
                    fontSize: 11,
                    color:
                      role === opt.v
                        ? 'color-mix(in oklab, var(--accent) 70%, var(--text))'
                        : 'var(--text-mute)',
                    marginTop: 4,
                    lineHeight: 1.5,
                  }}
                >
                  {opt.d}
                </div>
              </button>
            ))}
          </div>
        </Field>

        {role === 'RP_ADMIN' && (
          <Field
            label="할당 Tenant"
            hint="이 운영자는 선택한 tenant만 접근 가능합니다. 생성 후 변경하려면 새 운영자를 만들어야 합니다."
          >
            <select
              className="input"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
            >
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} · {t.slug}
                </option>
              ))}
            </select>
            {selectedTenant && (
              <div
                style={{
                  marginTop: 8,
                  padding: '8px 12px',
                  background: 'var(--surface-3)',
                  borderRadius: 6,
                  fontSize: 12,
                  color: 'var(--text-soft)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                }}
              >
                <div
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 5,
                    background: 'var(--accent-soft)',
                    color: 'var(--accent)',
                    display: 'grid',
                    placeItems: 'center',
                    fontWeight: 700,
                    fontSize: 10,
                  }}
                >
                  {selectedTenant.name.slice(0, 1)}
                </div>
                <span>
                  로그인 즉시 <strong>{selectedTenant.name}</strong>의 상세 페이지로 자동
                  라우팅됩니다.
                </span>
              </div>
            )}
          </Field>
        )}

        <Field label="보안">
          <label
            style={{
              display: 'flex',
              gap: 10,
              padding: '10px 12px',
              background: requireMfa ? 'var(--success-soft)' : 'var(--surface-3)',
              borderRadius: 8,
              alignItems: 'flex-start',
              cursor: 'pointer',
              border: `1px solid ${requireMfa ? 'color-mix(in oklab, var(--success) 25%, transparent)' : 'var(--border)'}`,
            }}
          >
            <input
              type="checkbox"
              checked={requireMfa}
              onChange={(e) => setRequireMfa(e.target.checked)}
              style={{ marginTop: 2 }}
            />
            <div style={{ fontSize: 13 }}>
              <div style={{ fontWeight: 600, color: requireMfa ? 'var(--success)' : 'var(--text)' }}>
                MFA 필수 (권장)
              </div>
              <div style={{ color: 'var(--text-mute)', marginTop: 2, fontSize: 12 }}>
                최초 로그인 시 TOTP authenticator 등록이 강제됩니다. 보안 정책에서 일괄 변경 가능.
              </div>
            </div>
          </label>
        </Field>

        <div
          style={{
            padding: '10px 12px',
            background: 'var(--info-soft)',
            color: 'var(--info)',
            borderRadius: 6,
            fontSize: 12,
            display: 'flex',
            gap: 8,
          }}
        >
          <Icons.Info size={14} />
          <div>
            <div style={{ fontWeight: 600 }}>다음 단계</div>
            <div style={{ marginTop: 2, color: 'var(--text-soft)' }}>
              생성 즉시{' '}
              <code
                style={{
                  background: 'rgba(0,0,0,0.06)',
                  padding: '1px 4px',
                  borderRadius: 3,
                  fontFamily: 'var(--mono)',
                }}
              >
                {email || 'user@example.com'}
              </code>
              로 초대 메일이 발송되며, 24시간 내에 비밀번호 설정 + MFA 등록이 필요합니다. 모든
              단계는 audit log에 기록됩니다.
            </div>
          </div>
        </div>
      </div>
    </Dialog>
  );
}

// ── InvitationModal ────────────────────────────────────────────────────────────

function InvitationModal({
  info,
  onClose,
}: {
  info: InvitationInfo;
  onClose: () => void;
}) {
  const [confirmed, setConfirmed] = useState(false);
  const [copied, setCopied] = useState(false);

  function copyToClipboard(text: string) {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  return (
    <Dialog
      open
      onClose={onClose}
      closeOnScrim={false}
      wide
      title="초대 링크 발급 완료"
      sub="초대 링크는 지금만 표시됩니다. 이 창을 닫으면 다시 볼 수 없습니다."
      footer={
        <button className="btn btn--primary" disabled={!confirmed} onClick={onClose}>
          확인했습니다
        </button>
      }
    >
      <div className="stack-3">
        <div
          style={{
            padding: '10px 12px',
            background: 'var(--warning-soft)',
            color: 'var(--warning)',
            borderRadius: 6,
            fontSize: 12,
            display: 'flex',
            gap: 8,
            alignItems: 'flex-start',
          }}
        >
          <span style={{ flex: 'none', marginTop: 1, display: 'flex' }}>
            <Icons.Alert size={14} />
          </span>
          <div>
            <div style={{ fontWeight: 600 }}>보안 주의</div>
            <div style={{ marginTop: 2, color: 'var(--text-soft)' }}>
              아래 링크는 한 번만 표시됩니다. 운영자에게 안전한 채널로 전달하세요.
              24시간 후 만료됩니다.
            </div>
          </div>
        </div>

        <div>
          <div className="label" style={{ marginBottom: 6 }}>초대 링크 (Accept URL)</div>
          <div
            style={{
              display: 'flex',
              gap: 8,
              alignItems: 'center',
              padding: '10px 12px',
              background: 'var(--surface-3)',
              borderRadius: 6,
              border: '1px solid var(--border)',
            }}
          >
            <code
              style={{
                flex: 1,
                fontSize: 12,
                fontFamily: 'var(--mono)',
                wordBreak: 'break-all',
                color: 'var(--text)',
              }}
            >
              {info.acceptUrl}
            </code>
            <button
              className="btn btn--sm"
              onClick={() => copyToClipboard(info.acceptUrl)}
              style={{ flex: 'none' }}
            >
              {copied ? <Icons.Check size={12} /> : <Icons.Copy size={12} />}
              {copied ? '복사됨' : '복사'}
            </button>
          </div>
        </div>

        <div>
          <div className="label" style={{ marginBottom: 6 }}>토큰 접두어</div>
          <code
            style={{
              display: 'block',
              padding: '6px 10px',
              background: 'var(--surface-3)',
              borderRadius: 4,
              fontSize: 12,
              fontFamily: 'var(--mono)',
            }}
          >
            {info.tokenPrefix}…
          </code>
        </div>

        <div
          style={{
            fontSize: 12,
            color: 'var(--text-mute)',
          }}
        >
          만료:{' '}
          {new Date(info.expiresAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}
        </div>

        <label
          style={{
            display: 'flex',
            gap: 10,
            padding: '10px 12px',
            background: confirmed ? 'var(--success-soft)' : 'var(--surface-3)',
            borderRadius: 8,
            alignItems: 'flex-start',
            cursor: 'pointer',
            border: `1px solid ${confirmed ? 'color-mix(in oklab, var(--success) 25%, transparent)' : 'var(--border)'}`,
          }}
        >
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
            style={{ marginTop: 2 }}
          />
          <div style={{ fontSize: 13 }}>
            <div style={{ fontWeight: 600, color: confirmed ? 'var(--success)' : 'var(--text)' }}>
              초대 링크를 안전하게 전달했습니다
            </div>
            <div style={{ color: 'var(--text-mute)', marginTop: 2, fontSize: 12 }}>
              이 창을 닫으면 초대 링크를 다시 볼 수 없습니다.
            </div>
          </div>
        </label>
      </div>
    </Dialog>
  );
}
