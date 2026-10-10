// admin-ui/src/pages/settings/AccountTab.tsx
import { useState } from 'react';
import { mfaApi, type EnrollResponse } from '@/api/mfa';
import { getMe } from '@/api/client';
import { ApiError, type Me } from '@/api/types';
import { QrCode } from '@/shell/QrCode';
import { illustrations } from '@/assets/illustrations';
import { Badge, Button, Dialog, OtpInput, SecretBox, Stepper, useToast, useUnsavedGuard, type StepState } from '@/ui';
import { KvList, type KvItem } from '@/pages/tenant/KvList';
import { RecoveryCodesModal } from './RecoveryCodesModal';

function sanitizeCode(v: string): string {
  return v.replace(/\D/g, '').slice(0, 6);
}

/** 켜기 다이얼로그(1·2단계)의 진행 표시. 3단계(복구 코드)는 별도 alertdialog 로 넘어간다. */
function enrollSteps(code: string): { label: string; description: string; state: StepState }[] {
  const scanned = code.length > 0;
  return [
    { label: 'QR 스캔', description: '앱에 계정 추가', state: scanned ? 'done' : 'now' },
    { label: '코드 확인', description: '6자리 입력', state: scanned ? 'now' : 'todo' },
    { label: '복구 코드 저장', description: '10개 · 1회 표시', state: 'todo' },
  ];
}

export default function AccountTab({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const toast = useToast();

  // enroll flow state
  const [enroll, setEnroll] = useState<EnrollResponse | null>(null);
  const [enrollCode, setEnrollCode] = useState('');
  const [enrolling, setEnrolling] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  // 복구 코드는 1회만 표시된다 — 닫기 전 이동·새로고침을 막는다.
  const leaveGuard = useUnsavedGuard(recoveryCodes !== null, {
    title: '복구 코드를 아직 저장하지 않았습니다',
    body: '이동하면 복구 코드를 다시 볼 수 없습니다.',
  });

  // disable flow state
  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [disabling, setDisabling] = useState(false);

  async function startEnroll() {
    setEnrolling(true);
    try {
      const res = await mfaApi.enroll();
      setEnroll(res);
      setEnrollCode('');
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '2단계 인증 시작 실패', message: msg });
    } finally {
      setEnrolling(false);
    }
  }

  function cancelEnroll() {
    setEnroll(null);
    setEnrollCode('');
  }

  async function confirmEnroll() {
    setConfirming(true);
    try {
      const res = await mfaApi.confirm(enrollCode);
      setEnroll(null);
      setEnrollCode('');
      // 복구 코드는 1회만 표시 — me 갱신 실패와 무관하게 먼저 보여 준다.
      if (res.recoveryCodes && res.recoveryCodes.length > 0) {
        setRecoveryCodes(res.recoveryCodes);
      }
      toast({ kind: 'ok', title: '2단계 인증이 켜졌습니다.' });
      try {
        onMeChange(await getMe());
      } catch {
        toast({ kind: 'warn', title: '계정 정보를 새로고침하지 못했습니다.', message: '페이지를 새로고침해 주세요.' });
      }
    } catch (e: unknown) {
      setEnrollCode('');
      if (e instanceof ApiError) {
        toast({ kind: 'err', title: '코드가 올바르지 않습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '2단계 인증 확인 실패', message: msg });
      }
    } finally {
      setConfirming(false);
    }
  }

  function openDisable() {
    setDisableCode('');
    setDisableOpen(true);
  }

  async function confirmDisable() {
    setDisabling(true);
    try {
      await mfaApi.disable(disableCode);
      setDisableOpen(false);
      setDisableCode('');
      onMeChange(await getMe());
      toast({ kind: 'warn', title: '2단계 인증이 꺼졌습니다.' });
    } catch (e: unknown) {
      setDisableCode('');
      if (e instanceof ApiError) {
        toast({ kind: 'err', title: '코드가 올바르지 않습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '2단계 인증 끄기 실패', message: msg });
      }
    } finally {
      setDisabling(false);
    }
  }

  // §6 ④ — 서버가 내려줄 때만 표시(null/undefined 면 줄 자체를 렌더하지 않음)
  const enrolledAt = me.mfaEnrolledAt != null ? me.mfaEnrolledAt.slice(0, 10) : null;
  const remaining = me.recoveryCodesRemaining != null ? me.recoveryCodesRemaining : null;
  const infoRows: KvItem[] = [
    ...(enrolledAt !== null ? [{ k: '등록일', v: enrolledAt }] : []),
    ...(remaining !== null ? [{ k: '남은 복구 코드', v: `${remaining} / 10` }] : []),
  ];
  const hasInfoRows = infoRows.length > 0;

  return (
    <div className="stack-4">
      <section className="card">
        <div className="set">
          <div className="stack-1" style={{ gap: 6 }}>
            <div className="row" style={{ gap: 8 }}>
              <h2 className="card__title">2단계 인증 (MFA)</h2>
              {me.mfaEnabled ? <Badge tone="ok">켜짐</Badge> : <Badge>꺼짐</Badge>}
            </div>
            <p className="card__sub">
              {me.mfaEnabled
                ? '로그인 시 authenticator 코드가 필요합니다.'
                : '로그인 시 비밀번호에 더해 authenticator 앱의 6자리 코드를 요구합니다.'}
            </p>
          </div>

          {!me.mfaEnabled && (
            <div
              className="row"
              style={{ gap: 20, flexWrap: 'nowrap', alignItems: 'center', padding: 16, border: '1px dashed var(--brand-300)', borderRadius: 'var(--r)', background: 'var(--brand-50)' }}
            >
              <img src={illustrations.mfa} alt="" style={{ width: 96, height: 96, objectFit: 'contain', flex: 'none' }} />
              <div className="stack-2" style={{ flex: '1 1 0', minWidth: 0 }}>
                <strong>계정을 한 단계 더 보호하세요</strong>
                <span className="card__sub">3단계로 끝납니다 — 앱으로 QR 스캔 → 코드 확인 → 복구 코드 저장.</span>
                <div className="row">
                  <Button variant="primary" onClick={() => void startEnroll()} disabled={enrolling}>2단계 인증 켜기</Button>
                </div>
              </div>
            </div>
          )}

          {me.mfaEnabled && (
            <div className="stack-3" style={{ gap: 14 }}>
              {hasInfoRows && <KvList items={infoRows} keyWidth={140} />}
              <div style={hasInfoRows ? { borderTop: '1px solid var(--line-soft)', paddingTop: 14 } : undefined}>
                <span className="t-over" style={{ color: 'var(--bad)' }}>위험 영역</span>
                <div className="row" style={{ gap: 12, marginTop: 6 }}>
                  <span className="card__sub" style={{ flex: '1 1 220px' }}>
                    끄면 비밀번호만으로 로그인됩니다. 현재 authenticator 코드가 필요합니다.
                  </span>
                  <Button variant="danger" onClick={openDisable} disabled={disabling}>2단계 인증 끄기</Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </section>

      {/* ── 켜기: 1–2 / 3 (QR 스캔 + 코드 확인) ─────────────────────────── */}
      <Dialog open={enroll !== null} onClose={() => { if (!confirming) cancelEnroll(); }} wide="x" ariaLabel="2단계 인증 켜기">
        {enroll && (
          <div style={{ display: 'grid', gridTemplateColumns: '220px minmax(0, 1fr)', margin: '-16px -24px -20px' }}>
            <div style={{ background: 'var(--canvas)', borderRight: '1px solid var(--line-soft)', padding: '24px 18px' }}>
              <Stepper title="2단계 인증 켜기" steps={enrollSteps(enrollCode)} />
            </div>
            <div className="col" style={{ minWidth: 0 }}>
              <div className="dialog__head">
                <span className="hint" style={{ margin: 0 }}>1–2 / 3</span>
                <h3 className="dialog__title">앱으로 스캔하고 코드 확인</h3>
              </div>
              <div className="dialog__body">
                <div style={{ display: 'grid', gridTemplateColumns: '180px minmax(0, 1fr)', gap: 20, alignItems: 'start' }}>
                  <div className="stack-2" style={{ alignItems: 'center' }}>
                    <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', lineHeight: 0 }}>
                      <QrCode value={enroll.otpauthUri} size={152} />
                    </div>
                    <span className="hint" style={{ margin: 0 }}>QR 코드</span>
                  </div>
                  <div className="stack-2" style={{ gap: 10, minWidth: 0 }}>
                    <span className="label" style={{ margin: 0 }}>또는 수동 입력 키</span>
                    <SecretBox value={enroll.secret} copyLabel="복사" />
                    <p className="hint" style={{ margin: 0 }}>Google Authenticator · 1Password 등 TOTP 앱에 입력하세요.</p>
                  </div>
                </div>
                <div style={{ borderTop: '1px solid var(--line-soft)', paddingTop: 16 }}>
                  <span className="label">앱에 표시된 인증 코드</span>
                  <div style={{ display: 'inline-flex' }}>
                    <OtpInput value={enrollCode} onChange={setEnrollCode} ariaLabel="인증 코드" disabled={confirming} />
                  </div>
                </div>
              </div>
              <div className="dialog__foot">
                <Button onClick={cancelEnroll} disabled={confirming}>취소</Button>
                <Button variant="primary" onClick={() => void confirmEnroll()} disabled={enrollCode.length !== 6 || confirming}>
                  확인 → 복구 코드
                </Button>
              </div>
            </div>
          </div>
        )}
      </Dialog>

      {/* ── 끄기 ─────────────────────────────────────────────────────────── */}
      <Dialog
        open={disableOpen}
        onClose={() => { if (!disabling) setDisableOpen(false); }}
        role="alertdialog"
        title="2단계 인증 끄기"
        sub="본인 확인을 위해 현재 authenticator 코드를 입력하세요."
        footer={
          <>
            <Button onClick={() => setDisableOpen(false)} disabled={disabling}>취소</Button>
            <Button variant="danger-solid" onClick={() => void confirmDisable()} disabled={disableCode.length !== 6 || disabling}>끄기</Button>
          </>
        }
      >
        <div>
          <label className="label" htmlFor="mfa-disable-code">인증 코드</label>
          <input
            id="mfa-disable-code"
            className="input mono"
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            value={disableCode}
            onChange={(e) => setDisableCode(sanitizeCode(e.target.value))}
            style={{ maxWidth: 200, letterSpacing: '0.2em' }}
          />
        </div>
      </Dialog>

      {/* ── 3 / 3: 복구 코드 ─────────────────────────────────────────────── */}
      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} accountEmail={me.email} onClose={() => setRecoveryCodes(null)} />
      )}
      {leaveGuard}
    </div>
  );
}
