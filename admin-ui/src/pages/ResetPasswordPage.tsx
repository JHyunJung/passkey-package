import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button, Field } from '@/ui';

const MIN_LENGTH = 12;

/** 3칸 길이 미터: 6자당 1칸, 12자 이상이면 ok 색. 문구로도 충족 여부를 알린다(색만으로 전달 금지). */
function LengthMeter({ id, length }: { id: string; length: number }) {
  const ok = length >= MIN_LENGTH;
  const filled = Math.min(3, Math.floor(length / 6));
  return (
    <div id={id} className="row" style={{ gap: 6, marginTop: 8, flexWrap: 'nowrap' }}>
      {[0, 1, 2].map((i) => (
        <span key={i} aria-hidden="true" style={{ flex: '1 1 0', height: 4, borderRadius: 2, background: i < filled ? (ok ? 'var(--ok)' : 'var(--warn-dot)') : 'var(--line)' }} />
      ))}
      <span className="hint" style={{ margin: 0, flex: 'none', color: length > 0 && !ok ? 'var(--bad)' : undefined }}>
        {ok ? '12자 이상 충족' : '12자 이상'}
      </span>
    </div>
  );
}

/**
 * 백엔드 PasswordResetService.confirm 의 토큰 거부(invalid token · token already used · token expired)는
 * IllegalArgumentException → 400 + 메시지 그대로. 토큰 사전 확인 API 는 2단계(스펙 §8).
 */
function isTokenRejection(e: unknown): boolean {
  return e instanceof ApiError && e.httpStatus === 400 && /token/i.test(e.serverMessage);
}

type Failure =
  | { kind: 'expired' }
  | { kind: 'transient'; error: unknown }
  | { kind: 'rejected'; message: string };

/**
 * 미인증 public 화면 — 이메일 링크의 ?token= 으로 진입해 새 비밀번호 설정.
 * 클라이언트 검증: 12자 이상, 두 입력 일치.
 * 토큰 거부(400) → 만료 카드, 5xx·네트워크 → 다시 시도(입력 유지), 그 밖의 거부 → 서버 메시지.
 * 모든 상태에 "← 로그인으로"(H3).
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const tooShort = pw.length > 0 && pw.length < MIN_LENGTH;
  const canSubmit = !!token && pw.length >= MIN_LENGTH && pw === pw2 && !submitting;

  async function save() {
    if (!canSubmit) return;
    setSubmitting(true);
    setFailure(null);
    try {
      await passwordResetApi.confirm(token!, pw);
      setDone(true);
    } catch (err: unknown) {
      if (isTransientError(err)) setFailure({ kind: 'transient', error: err });
      else if (isTokenRejection(err)) setFailure({ kind: 'expired' });
      else setFailure({ kind: 'rejected', message: err instanceof ApiError ? err.serverMessage : '비밀번호 변경에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void save();
  }

  const linkUnusable = !token || failure?.kind === 'expired';
  const transientStatus = failure?.kind === 'transient' && failure.error instanceof ApiError ? failure.error.httpStatus : undefined;

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card">
          {linkUnusable ? (
            <>
              <Alert tone="warn" title="링크를 사용할 수 없습니다">
                {token
                  ? '링크가 만료되었거나 이미 사용되었습니다. 보안을 위해 재설정 링크는 정해진 시간 안에 한 번만 쓸 수 있습니다.'
                  : '유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.'}
              </Alert>
              <a className="btn btn--primary" href="/admin/forgot-password">재설정 링크 다시 받기</a>
            </>
          ) : done ? (
            <>
              <Alert tone="ok" title="변경 완료">비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.</Alert>
              <a className="btn btn--primary" href="/admin">로그인 →</a>
            </>
          ) : (
            <form className="col" style={{ gap: 20 }} onSubmit={submit}>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">새 비밀번호 설정</h1>
                <p className="card__sub">12자 이상으로 새 비밀번호를 입력하세요.</p>
              </div>
              {failure?.kind === 'transient' && (
                <Alert tone="bad" title="비밀번호를 바꾸지 못했습니다."
                  actions={<Button size="sm" onClick={() => void save()} disabled={!canSubmit}>다시 시도</Button>}>
                  서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.
                  {transientStatus !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {transientStatus}</span>}
                </Alert>
              )}
              <div>
                <label className="label" htmlFor="reset-pw">새 비밀번호</label>
                <input
                  id="reset-pw"
                  className="input input--lg"
                  type="password"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  value={pw}
                  onChange={(e) => setPw(e.target.value)}
                  autoComplete="new-password"
                  readOnly={submitting}
                  aria-invalid={tooShort}
                  aria-describedby="reset-pw-meter"
                />
                <LengthMeter id="reset-pw-meter" length={pw.length} />
              </div>
              <Field label="새 비밀번호 확인" htmlFor="reset-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input
                  id="reset-pw2"
                  className="input input--lg"
                  type="password"
                  value={pw2}
                  onChange={(e) => setPw2(e.target.value)}
                  autoComplete="new-password"
                  readOnly={submitting}
                  aria-invalid={mismatch}
                  aria-describedby={mismatch ? 'reset-pw2-error' : undefined}
                />
              </Field>
              {failure?.kind === 'rejected' && <Alert tone="bad">{failure.message}</Alert>}
              <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '변경 중…' : '비밀번호 변경'}
              </Button>
            </form>
          )}
        </div>
        <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>
      </div>
    </main>
  );
}
