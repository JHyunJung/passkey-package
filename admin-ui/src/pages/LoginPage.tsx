// admin-ui/src/pages/LoginPage.tsx
import { useState, useEffect, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { HELP_LINKS } from '@/config/links';
import { describeError } from '@/lib/describeError';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button } from '@/ui';

type LoginPageProps = {
  onLogin: (me: Me) => void;
};

// 브랜드 패널 — 로그인 전에는 실제 지표를 조회할 수 없으므로 숫자 대신 콘솔이 하는 일을 적는다(H2).
const DUTIES = [
  'tenant 생성 · 정책 설정 · API 키 발급',
  '사용자 패스키 조회와 회수',
  '감사 로그 무결성 확인과 내보내기',
] as const;

const DEMO_ACCOUNTS = [
  { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
] as const;

type LoginError =
  | { kind: 'credentials' }
  | { kind: 'transient'; status?: number }
  | { kind: 'other'; message: string };

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [demoRole, setDemoRole] = useState<'PLATFORM_OPERATOR' | 'RP_ADMIN' | null>(null);
  const [devProfile, setDevProfile] = useState(false);
  const [error, setError] = useState<LoginError | null>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

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
    setError(null);
    if (devProfile && role === 'PLATFORM_OPERATOR') {
      setEmail('alice@crosscert.com');
      setPassword('alice-temp-pw');
    }
  }

  async function attempt() {
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      const res = await api.loginForm(email, password);
      if (!res.ok) {
        if (res.status >= 500) { setError({ kind: 'transient', status: res.status }); return; }
        // 401 등(423/429 잠김 구분은 백엔드 2단계) — 이메일은 두고 비밀번호만 지운 뒤 비밀번호 칸으로.
        setError({ kind: 'credentials' });
        setPassword('');
        passwordRef.current?.focus();
        return;
      }
      onLogin(await getMe());
    } catch (err: unknown) {
      if (isTransientError(err)) {
        setError({ kind: 'transient', status: err instanceof ApiError ? err.httpStatus : undefined });
      } else {
        const info = describeError(err);
        setError({ kind: 'other', message: `${info.title}. ${info.detail}` });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    void attempt();
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
            RP 별 tenant · 패스키 · API 키를 한 곳에서 관리하고, 모든 변경은 위·변조를 확인할 수 있는 감사 로그로 남습니다.
          </p>
        </div>
        <img src={illustrations.loginHero} alt="" style={{ width: '100%', maxWidth: 420, height: 'auto', alignSelf: 'center' }} />
        <div className="col" style={{ gap: 8, paddingTop: 18, borderTop: '1px solid var(--line)', marginTop: 'auto' }}>
          <span className="t-over">이 콘솔에서 하는 일</span>
          <ul style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 14, color: 'var(--ink-2)' }}>
            {DUTIES.map((d) => <li key={d}>{d}</li>)}
          </ul>
          <span className="hint" style={{ margin: 0 }}>실시간 지표는 로그인 후 확인할 수 있습니다.</span>
        </div>
        <p className="hint" style={{ margin: 0 }}>© 2026 Crosscert · 이 콘솔의 모든 접근은 감사 로그에 기록됩니다.</p>
      </aside>

      {/* 폼 */}
      <main style={{ flex: '1 1 440px', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '48px 24px', boxSizing: 'border-box' }}>
        <form onSubmit={handleSubmit} className="col" style={{ width: '100%', maxWidth: 380, gap: 20 }}>
          <div className="col" style={{ gap: 6 }}>
            <h2 className="page__title">관리자 로그인</h2>
            <p className="card__sub">등록된 운영자 계정으로 로그인하세요.</p>
          </div>

          {/* 오류는 폼 위 한 곳에만(H4·H9). 토스트로 중복하지 않는다. */}
          {error?.kind === 'credentials' && (
            <Alert tone="bad" title="로그인 실패">
              이메일 또는 비밀번호가 올바르지 않습니다. <span className="mono" style={{ fontSize: 11 }}>A001</span>
            </Alert>
          )}
          {error?.kind === 'transient' && (
            <Alert tone="bad" title="지금은 로그인할 수 없습니다"
              actions={<Button size="sm" onClick={() => void attempt()} disabled={submitting}>다시 시도</Button>}>
              서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.
              {error.status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {error.status}</span>}
            </Alert>
          )}
          {error?.kind === 'other' && <Alert tone="bad" title="로그인 실패">{error.message}</Alert>}

          <div>
            <label className="label" htmlFor="login-email">이메일</label>
            <input
              id="login-email"
              className="input input--lg"
              type="email"
              // 로그인 화면의 유일한 할 일이라 바로 입력할 수 있게 한다(H1).
              // eslint-disable-next-line jsx-a11y/no-autofocus
              autoFocus
              readOnly={submitting}
              value={email}
              onChange={(e) => { setEmail(e.target.value); setError(null); }}
              autoComplete="username"
              placeholder="you@crosscert.com"
            />
          </div>
          <div>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
              <label className="label" htmlFor="login-password">비밀번호</label>
              <a href="/admin/forgot-password" style={{ fontSize: 12, fontWeight: 500, marginBottom: 6 }}>비밀번호를 잊으셨나요?</a>
            </div>
            <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
              <input
                id="login-password"
                ref={passwordRef}
                className="input input--lg"
                type={showPw ? 'text' : 'password'}
                value={password}
                onChange={(e) => { setPassword(e.target.value); setError(null); }}
                autoComplete="current-password"
                readOnly={submitting}
                placeholder="비밀번호"
                style={{ flex: '1 1 0', minWidth: 0 }}
              />
              <Button size="lg" aria-pressed={showPw} aria-label={showPw ? '비밀번호 숨기기' : '비밀번호 표시'} onClick={() => setShowPw((v) => !v)}>
                {showPw ? '숨기기' : '표시'}
              </Button>
            </div>
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
          <div className="row" style={{ justifyContent: 'space-between', gap: 8, paddingTop: 14, borderTop: '1px solid var(--line-soft)' }}>
            <p className="hint" style={{ margin: 0 }}>30분 동안 활동이 없으면 자동으로 로그아웃됩니다.</p>
            {HELP_LINKS.support && <a href={HELP_LINKS.support} style={{ fontSize: 12 }}>도움말 · 운영자에게 문의</a>}
          </div>
        </form>
      </main>
    </div>
  );
}
