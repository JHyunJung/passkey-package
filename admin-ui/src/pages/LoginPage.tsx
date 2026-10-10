// admin-ui/src/pages/LoginPage.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { Alert, Button, useToast } from '@/ui';

type LoginPageProps = {
  onLogin: (me: Me) => void;
};

// 브랜드 패널 지표 — 현재 화면에 표시하던 값 그대로(로그인 전이라 API 조회 없음).
const STATS = [
  { value: '58', label: '활성 tenant' },
  { value: '2.4M', label: 'ceremony / 24h' },
  { value: '100%', label: 'chain 무결성' },
] as const;

const DEMO_ACCOUNTS = [
  { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
] as const;

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
    <div style={{ minHeight: '100vh', display: 'flex', flexWrap: 'wrap', background: 'var(--surface)' }}>
      {/* 브랜드 패널 */}
      <aside style={{ flex: '1 1 440px', background: 'var(--brand-50)', borderRight: '1px solid var(--line)', padding: '48px 56px', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 24 }}>
        <img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" style={{ width: 156, height: 'auto' }} />
        <div className="col" style={{ gap: 10, marginTop: 12 }}>
          <h1 style={{ margin: 0, fontSize: 34, lineHeight: '44px', fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--ink)' }}>
            패스키 인증을<br />운영하는 콘솔.
          </h1>
          <p style={{ margin: 0, fontSize: 15, lineHeight: '24px', color: 'var(--ink-2)', maxWidth: 420 }}>
            RP 별 tenant · credential · API 키를 한 곳에서 관리하고, 모든 조작은 hash chain 감사 로그로 남습니다.
          </p>
        </div>
        <img src={illustrations.loginHero} alt="" style={{ width: '100%', maxWidth: 420, height: 'auto', alignSelf: 'center' }} />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 16, paddingTop: 18, borderTop: '1px solid var(--line)', marginTop: 'auto' }}>
          {STATS.map((s) => (
            <div key={s.label} className="col">
              <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--ink)' }}>{s.value}</span>
              <span className="hint" style={{ margin: 0 }}>{s.label}</span>
            </div>
          ))}
        </div>
        <p className="hint" style={{ margin: 0 }}>© 2026 Crosscert · 본 콘솔 접근은 모두 audit log에 기록됩니다.</p>
      </aside>

      {/* 폼 */}
      <main style={{ flex: '1 1 440px', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '48px 24px', boxSizing: 'border-box' }}>
        <form onSubmit={handleSubmit} className="col" style={{ width: '100%', maxWidth: 380, gap: 20 }}>
          <div className="col" style={{ gap: 6 }}>
            <h2 className="page__title">관리자 로그인</h2>
            <p className="card__sub">등록된 운영자 계정으로 로그인하세요.</p>
          </div>

          {error && (
            <Alert tone="bad" title="로그인 실패">
              {error.message} <span className="mono" style={{ fontSize: 11 }}>{error.code}</span>
            </Alert>
          )}

          <div>
            <label className="label" htmlFor="login-email">이메일</label>
            <input
              id="login-email"
              className="input input--lg"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              placeholder="you@crosscert.com"
            />
          </div>
          <div>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
              <label className="label" htmlFor="login-password">비밀번호</label>
              <a href="/admin/forgot-password" style={{ fontSize: 12, fontWeight: 500, marginBottom: 6 }}>비밀번호를 잊으셨나요?</a>
            </div>
            <input
              id="login-password"
              className="input input--lg"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              placeholder="••••••••"
            />
          </div>

          <Button type="submit" variant="primary" size="lg" disabled={submitting} style={{ width: '100%' }}>
            {submitting ? '확인 중…' : '로그인'}
          </Button>

          {/* 데모 계정 — dev/local 프로필에서만 */}
          {devProfile && DEMO_ACCOUNTS.map((opt) => {
            const on = demoRole === opt.v;
            return (
              <button
                key={opt.v}
                type="button"
                aria-pressed={on}
                onClick={() => selectDemo(opt.v)}
                className="row"
                style={{
                  flexWrap: 'nowrap', gap: 10, width: '100%', padding: '12px 14px', boxSizing: 'border-box',
                  border: `1px dashed ${on ? 'var(--brand-700)' : 'var(--input-line)'}`, borderRadius: 'var(--r)',
                  background: on ? 'var(--brand-50)' : 'var(--canvas)', color: 'var(--ink)', font: 'inherit', textAlign: 'left', cursor: 'pointer',
                }}
              >
                <span className="tag">DEV</span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>데모 계정으로 로그인</span>
                  <span className="mono hint truncate" style={{ margin: 0 }}>{opt.t} / {opt.s}</span>
                </span>
                <span style={{ color: 'var(--mute)', display: 'inline-flex' }}><Icons.ChevronRight size={16} /></span>
              </button>
            );
          })}

          <p className="card__sub" style={{ textAlign: 'center' }}>
            계정이 없으신가요? <a href="/admin/signup" style={{ fontWeight: 600 }}>가입 요청</a>
          </p>
          <p className="hint" style={{ margin: 0, textAlign: 'center', paddingTop: 14, borderTop: '1px solid var(--line-soft)' }}>
            30분 동안 활동이 없으면 자동으로 로그아웃됩니다.
          </p>
        </form>
      </main>
    </div>
  );
}
