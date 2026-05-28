import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { invitationApi } from '../api/adminUser';
import { ApiError } from '../api/types';
import type { InvitationCheck } from '../api/types';
import { useToast } from '../components/Toast';
import { BrandMark } from '../components/Icons';
import { formatDateTime } from '../lib/formatDateTime';

type PageState = 'loading' | 'form' | 'expired' | 'error';

export default function AcceptInvite() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const nav = useNavigate();
  const toast = useToast();

  const [pageState, setPageState] = useState<PageState>('loading');
  const [info, setInfo] = useState<InvitationCheck | null>(null);
  const [errorMsg, setErrorMsg] = useState('');

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [pwError, setPwError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setErrorMsg('초대 링크가 올바르지 않습니다. token 파라미터가 없습니다.');
      setPageState('error');
      return;
    }
    invitationApi
      .check(token)
      .then((data) => {
        setInfo(data);
        setPageState('form');
      })
      .catch((err: unknown) => {
        const e = err instanceof ApiError ? err : null;
        const code = e?.code ?? '';
        if (code === 'A401' || code === 'I001' || (e?.httpStatus === 410)) {
          setErrorMsg('초대가 만료되었거나 이미 사용된 링크입니다.');
          setPageState('expired');
        } else if (e?.httpStatus === 404) {
          setErrorMsg('유효하지 않은 초대 링크입니다.');
          setPageState('expired');
        } else {
          setErrorMsg(e?.serverMessage ?? '초대 정보를 불러오지 못했습니다.');
          setPageState('error');
        }
      });
  }, [token]);

  function validatePassword(): boolean {
    if (password.length < 12) {
      setPwError('비밀번호는 최소 12자 이상이어야 합니다.');
      return false;
    }
    if (password !== confirm) {
      setPwError('비밀번호가 일치하지 않습니다.');
      return false;
    }
    setPwError(null);
    return true;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!validatePassword()) return;
    setBusy(true);
    try {
      await invitationApi.accept(token, password);
      toast({ kind: 'ok', title: '계정 활성화 완료', message: '로그인 페이지로 이동합니다.' });
      nav('/login');
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({
        kind: 'err',
        title: '계정 설정 실패',
        message: e?.serverMessage ?? String(err),
        traceId: e?.traceId,
      });
    } finally {
      setBusy(false);
    }
  }

  const roleLabel =
    info?.role === 'PLATFORM_OPERATOR' ? 'Platform Operator' : info?.role === 'RP_ADMIN' ? 'RP Admin' : info?.role ?? '';

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg)',
        padding: '40px 16px',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 480,
          background: 'var(--surface)',
          borderRadius: 'var(--radius-lg)',
          border: '1px solid var(--border-subtle)',
          padding: '40px 40px',
          boxShadow: '0 4px 24px rgba(0,0,0,0.08)',
        }}
      >
        {/* Header */}
        <div className="row" style={{ gap: 10, marginBottom: 32 }}>
          <BrandMark size={24} />
          <span style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.01em' }}>Passkey Admin</span>
        </div>

        {/* Loading */}
        {pageState === 'loading' && (
          <div className="stack-4" style={{ textAlign: 'center' }}>
            <div className="muted">초대 정보를 확인하는 중…</div>
          </div>
        )}

        {/* Expired / Error */}
        {(pageState === 'expired' || pageState === 'error') && (
          <div className="stack-4">
            <h2 style={{ fontSize: 20, fontWeight: 600, margin: 0, letterSpacing: '-0.01em' }}>
              {pageState === 'expired' ? '만료된 초대' : '초대 오류'}
            </h2>
            <div className={`banner banner--${pageState === 'expired' ? 'warning' : 'danger'}`}>
              <div className="banner__body">{errorMsg}</div>
            </div>
            <p style={{ fontSize: 13, color: 'var(--text-mute)', margin: 0 }}>
              관리자에게 새 초대 링크를 요청하거나{' '}
              <a href="/login" style={{ color: 'var(--accent)' }}>
                로그인 페이지
              </a>
              로 이동하세요.
            </p>
          </div>
        )}

        {/* Form */}
        {pageState === 'form' && info && (
          <div className="stack-5">
            <div>
              <h2 style={{ fontSize: 20, fontWeight: 600, margin: '0 0 6px', letterSpacing: '-0.01em' }}>
                관리자 계정 설정
              </h2>
              <p style={{ fontSize: 13, color: 'var(--text-mute)', margin: 0 }}>
                비밀번호를 설정하면 계정이 활성화됩니다.
              </p>
            </div>

            {/* Invitation preview */}
            <div
              className="card"
              style={{ padding: '14px 16px', background: 'var(--surface-2)', border: '1px solid var(--border-subtle)' }}
            >
              <div className="stack-2">
                <InfoRow label="이메일" value={info.email} />
                <InfoRow label="역할" value={roleLabel} />
                {info.tenantId && <InfoRow label="Tenant" value={info.tenantId} mono />}
                <InfoRow label="만료" value={formatDateTime(info.expiresAt)} />
              </div>
            </div>

            <form onSubmit={submit} className="stack-4">
              <div>
                <label className="label" htmlFor="accept-pw">
                  비밀번호
                </label>
                <input
                  id="accept-pw"
                  type="password"
                  className="input"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  minLength={12}
                  autoComplete="new-password"
                  placeholder="최소 12자"
                  autoFocus
                />
              </div>
              <div>
                <label className="label" htmlFor="accept-pw2">
                  비밀번호 확인
                </label>
                <input
                  id="accept-pw2"
                  type="password"
                  className="input"
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  required
                  autoComplete="new-password"
                  placeholder="동일한 비밀번호 입력"
                />
              </div>
              {pwError && (
                <div className="banner banner--danger" role="alert">
                  {pwError}
                </div>
              )}
              <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
                {busy ? '처리 중…' : '계정 활성화'}
              </button>
            </form>

            <div className="banner banner--info" style={{ fontSize: 12 }}>
              <div className="banner__body">
                계정 활성화 후 로그인 페이지로 이동합니다. 이 링크는 1회만 사용할 수 있습니다.
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function InfoRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="row" style={{ gap: 8, justifyContent: 'space-between', fontSize: 13 }}>
      <span style={{ color: 'var(--text-mute)', minWidth: 60 }}>{label}</span>
      <span className={mono ? 'mono' : undefined} style={{ fontWeight: 500, textAlign: 'right' }}>
        {value}
      </span>
    </div>
  );
}
