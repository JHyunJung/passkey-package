import { useState, useEffect } from 'react';
import { Icons, BrandMark } from '@/icons/Icons';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import { useToast } from '@/shell/ToastHost';

type LoginPageProps = {
  onLogin: (me: Me) => void;
};

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [demoRole, setDemoRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN' | null>(null);
  const [devProfile, setDevProfile] = useState(false);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const toast = useToast();

  // dev/local profile 감지 — 데모 prefill + 데모 카드 노출 게이팅
  useEffect(() => {
    api.get<{ active: string[]; local?: boolean }>('/admin/api/profile')
      .then((p) => {
        if (p.active?.includes('dev') || p.active?.includes('local') || p.local) {
          setDevProfile(true);
        }
      })
      .catch(() => { /* prod 등 — 무시 */ });
  }, []);

  // 데모 role 카드 클릭 → 자동 prefill (dev/local 일 때만)
  function selectDemo(role: 'PLATFORM_OPERATOR' | 'RP_ADMIN') {
    setDemoRole(role);
    if (devProfile && role === 'PLATFORM_OPERATOR') {
      setEmail('alice@crosscert.com');
      setPassword('alice-temp-pw');
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      const ok = await api.loginForm(email, password);
      if (!ok) {
        const msg = '이메일 또는 비밀번호가 올바르지 않습니다.';
        setError({ code: 'A001', message: msg });
        toast({ kind: 'err', title: '로그인 실패', message: msg });
        return;
      }
      const me = await getMe();
      onLogin(me);
    } catch (err: unknown) {
      const e = err as { code?: string; serverMessage?: string; message?: string };
      const code = e?.code ?? 'C999';
      const message = e?.serverMessage || e?.message || '로그인 실패';
      setError({ code, message });
      toast({ kind: 'err', title: '로그인 실패', message });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      display: 'grid', gridTemplateColumns: '1.05fr 1fr',
      background: 'var(--bg)',
    }}>
      {/* Marketing-side panel */}
      <div style={{
        position: 'relative', overflow: 'hidden',
        background: 'linear-gradient(135deg, #1c1942 0%, #2e2a78 45%, #4f46e5 100%)',
        color: 'white', padding: '56px 64px',
        display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
      }}>
        <div className="row" style={{ gap: 10 }}>
          <BrandMark size={28} />
          <div style={{ fontWeight: 700, letterSpacing: '-0.01em', fontSize: 15 }}>Crosscert Passkey</div>
        </div>

        <div>
          <div style={{ fontSize: 13, opacity: 0.7, fontFamily: 'var(--mono)', marginBottom: 12 }}>v1.0 · multi-tenant FIDO2 server</div>
          <h1 style={{ fontSize: 38, fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1.15, margin: 0, maxWidth: 480 }}>
            패스키 인증을<br />운영하는 콘솔.
          </h1>
          <p style={{ fontSize: 14, opacity: 0.8, marginTop: 16, maxWidth: 460, lineHeight: 1.6 }}>
            tenant 온보딩, API key 회수, credential 폐기, audit hash chain 검증까지 한 곳에서.
            RP의 다음 ceremony가 시작되기 전에 끝낼 수 있도록.
          </p>
          <div style={{ display: 'flex', gap: 20, marginTop: 28 }}>
            <Stat label="활성 tenant" value="58" />
            <Stat label="ceremony / 24h" value="2.4M" />
            <Stat label="chain 무결성" value="100%" />
          </div>
        </div>

        <div style={{ fontSize: 12, opacity: 0.6 }}>© 2026 Crosscert · 본 콘솔 접근은 모두 audit log에 기록됩니다.</div>

        {/* abstract bg shape */}
        <svg style={{ position: 'absolute', right: -120, bottom: -140, width: 540, opacity: 0.18 }} viewBox="0 0 200 200">
          <defs>
            <linearGradient id="lg1" x1="0" x2="1" y1="0" y2="1">
              <stop stopColor="#fff" />
              <stop offset="1" stopColor="#fff" stopOpacity="0" />
            </linearGradient>
          </defs>
          <circle cx="100" cy="100" r="80" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="60" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="40" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="20" stroke="url(#lg1)" strokeWidth="1" fill="none" />
        </svg>
      </div>

      {/* Form side */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '40px' }}>
        <form onSubmit={handleSubmit} style={{ width: '100%', maxWidth: 380 }}>
          <img
            src="/admin/crosscert-logo-transparent.png"
            alt="CROSSCERT"
            style={{ height: 28, width: 'auto', display: 'block', marginBottom: 28 }}
          />
          <h2 style={{ fontSize: 24, fontWeight: 600, letterSpacing: '-0.01em', margin: 0 }}>관리자 로그인</h2>
          <p style={{ fontSize: 13, color: 'var(--text-mute)', marginTop: 6, marginBottom: 28 }}>
            Crosscert Passkey 콘솔에 접근하려면 운영자 계정으로 로그인하세요.
          </p>

          <div className="stack-3">
            <div>
              <label className="label">이메일</label>
              <input
                className="input"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
              />
            </div>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
                <label className="label">비밀번호</label>
                <a href="/admin/forgot-password" style={{ fontSize: 11, color: 'var(--accent)', textDecoration: 'none' }}>비밀번호를 잊으셨나요?</a>
              </div>
              <input
                className="input"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </div>

            {/* Role switcher — demo prefill (dev/local 만) */}
            {devProfile && (
              <div>
                <label className="label">데모 계정으로 로그인</label>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 8 }}>
                  {([
                    { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
                  ] as const).map((opt) => (
                    <button
                      key={opt.v}
                      type="button"
                      onClick={() => selectDemo(opt.v)}
                      style={{
                        padding: '8px 10px',
                        borderRadius: 8,
                        border: `1px solid ${demoRole === opt.v ? 'var(--accent)' : 'var(--border)'}`,
                        background: demoRole === opt.v ? 'var(--accent-soft)' : 'var(--surface)',
                        color: demoRole === opt.v ? 'var(--accent)' : 'var(--text)',
                        cursor: 'pointer',
                        textAlign: 'left',
                      }}
                    >
                      <div style={{ fontSize: 12, fontWeight: 600 }}>{opt.t}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-mute)', marginTop: 2 }}>{opt.s}</div>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {error && (
              <div style={{ display: 'flex', gap: 8, padding: '10px 12px', background: 'var(--danger-soft)', color: 'var(--danger)', borderRadius: 8, fontSize: 12 }}>
                <Icons.Alert size={14} />
                <div>
                  <div style={{ fontWeight: 600 }}>로그인 실패</div>
                  <div style={{ opacity: 0.85, marginTop: 2 }}>{error.code} · {error.message}</div>
                </div>
              </div>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="btn btn--primary"
              style={{ height: 38, justifyContent: 'center', marginTop: 4 }}
            >
              {submitting ? '확인 중…' : '로그인'}
            </button>
          </div>

          <div style={{ marginTop: 24, padding: '10px 12px', background: 'var(--surface-3)', borderRadius: 8, fontSize: 11, color: 'var(--text-mute)', display: 'flex', gap: 8 }}>
            <Icons.Info size={14} />
            <span>30분 동안 활동이 없으면 자동 로그아웃됩니다. 모든 mutation은 audit chain에 기록됩니다.</span>
          </div>
        </form>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontSize: 11, opacity: 0.7, letterSpacing: '0.06em', textTransform: 'uppercase', fontWeight: 600 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4 }}>{value}</div>
    </div>
  );
}
