import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, getMe } from '../api/client';
import { BrandMark } from '../components/Icons';

// V11 seed 계정 — local profile 일 때만 prefill. 운영 profile 에서는 응답이 local:false 라
// 절대 노출되지 않는다.
const LOCAL_PREFILL = { email: 'alice@crosscert.com', password: 'alice-temp-pw' };

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [isLocal, setIsLocal] = useState(false);
  const nav = useNavigate();

  useEffect(() => {
    fetch('/admin/api/profile', { credentials: 'include' })
      .then((r) => (r.ok ? r.json() : null))
      .then((env) => {
        if (env?.data?.local) {
          setIsLocal(true);
          setEmail(LOCAL_PREFILL.email);
          setPassword(LOCAL_PREFILL.password);
        }
      })
      .catch(() => { /* 무시 — local profile 아니면 prefill 없이 빈 폼 */ });
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const ok = await api.loginForm(email, password);
      if (!ok) {
        setError('이메일 또는 비밀번호가 올바르지 않습니다.');
        return;
      }
      const me = await getMe();
      if (me.role === 'RP_ADMIN' && me.tenantId) {
        nav(`/tenants/${me.tenantId}`);
      } else {
        nav('/tenants');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: '1fr 480px',
      minHeight: '100vh',
      background: 'var(--bg)',
    }}>
      {/* Hero */}
      <div style={{
        background: 'linear-gradient(135deg, oklch(0.42 0.18 268) 0%, oklch(0.30 0.13 268) 100%)',
        color: 'white',
        padding: '64px 56px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
      }}>
        <div className="row" style={{ gap: 12 }}>
          <BrandMark size={32} />
          <div style={{ fontSize: 18, fontWeight: 600, letterSpacing: '-0.01em' }}>Crosscert Passkey</div>
        </div>
        <div className="stack-3">
          <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.7)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
            v1.0 · multi-tenant FIDO2 server
          </div>
          <h1 style={{ fontSize: 34, fontWeight: 600, lineHeight: 1.2, margin: 0, letterSpacing: '-0.022em' }}>
            패스키 인증을<br/>운영하는 콘솔.
          </h1>
          <p style={{ fontSize: 14, color: 'rgba(255,255,255,0.78)', lineHeight: 1.6, maxWidth: 380 }}>
            tenant 온보딩, API key 회수, credential 폐기, audit hash chain 검증까지 한 곳에서.
          </p>
        </div>
        <div className="row" style={{ gap: 24, fontSize: 12, color: 'rgba(255,255,255,0.65)' }}>
          <span>© 2026 Crosscert</span>
          <span>본 콘솔 접근은 모두 audit log에 기록됩니다.</span>
        </div>
      </div>

      {/* Form */}
      <div style={{ background: 'var(--surface)', padding: '64px 48px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
        <h2 style={{ fontSize: 22, fontWeight: 600, marginTop: 0, marginBottom: 6, letterSpacing: '-0.01em' }}>관리자 로그인</h2>
        <p style={{ fontSize: 13, color: 'var(--text-mute)', marginTop: 0, marginBottom: 28 }}>
          Crosscert Passkey 콘솔에 접근하려면 운영자 계정으로 로그인하세요.
        </p>

        <form onSubmit={submit} className="stack-4">
          <div>
            <label htmlFor="login-email" className="label">이메일</label>
            <input
              id="login-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              className="input"
              placeholder="user@crosscert.com"
            />
          </div>
          <div>
            <label htmlFor="login-password" className="label">비밀번호</label>
            <input
              id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="input"
              placeholder="••••••••"
            />
          </div>
          <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
            {busy ? '로그인 중…' : '로그인'}
          </button>
          {error && <div className="banner banner--danger" role="alert">{error}</div>}
        </form>

        {isLocal && (
          <div className="banner banner--warning" style={{ marginTop: 28 }}>
            <div className="banner__body">
              <strong>local profile</strong> · V11 시드 계정 ({LOCAL_PREFILL.email}) 이 자동으로 입력되었습니다. prod 에서는 표시되지 않습니다.
            </div>
          </div>
        )}

        <div className="banner banner--info" style={{ marginTop: 28 }}>
          <div className="banner__body">
            30분 동안 활동이 없으면 자동 로그아웃됩니다. 모든 mutation은 audit chain에 기록됩니다.
          </div>
        </div>
      </div>
    </div>
  );
}
