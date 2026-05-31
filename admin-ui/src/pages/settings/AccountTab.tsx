import { useState } from 'react';
import { mfaApi, type EnrollResponse } from '@/api/mfa';
import { QrCode } from '@/shell/QrCode';
import { useToast } from '@/shell/ToastHost';
import { Dialog } from '@/shell/Dialog';
import { getMe } from '@/api/client';
import { ApiError, type Me } from '@/api/types';
import { RecoveryCodesModal } from './RecoveryCodesModal';

function sanitizeCode(v: string): string {
  return v.replace(/\D/g, '').slice(0, 6);
}

export default function AccountTab({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const toast = useToast();

  // enroll flow state
  const [enroll, setEnroll] = useState<EnrollResponse | null>(null);
  const [enrollCode, setEnrollCode] = useState('');
  const [enrolling, setEnrolling] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

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
      onMeChange(await getMe());
      toast({ kind: 'ok', title: '2단계 인증이 켜졌습니다.' });
      if (res.recoveryCodes && res.recoveryCodes.length > 0) {
        setRecoveryCodes(res.recoveryCodes);
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

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <h3 className="card__title">2단계 인증 (MFA)</h3>
          {me.mfaEnabled ? (
            <span className="badge badge--success">켜짐</span>
          ) : (
            <span className="badge badge--warning">꺼짐</span>
          )}
        </div>
        <div className="card__body stack-4">
          <div className="hint">
            인증 앱(Google Authenticator, 1Password 등)으로 6자리 코드를 생성하여 로그인 시 추가로 입력합니다.
          </div>

          {/* ── OFF: enroll/confirm ─────────────────────────────────── */}
          {!me.mfaEnabled && !enroll && (
            <div className="row">
              <button className="btn btn--primary" onClick={() => void startEnroll()} disabled={enrolling}>
                2단계 인증 켜기
              </button>
            </div>
          )}

          {!me.mfaEnabled && enroll && (
            <div className="stack-3">
              <div className="hint">인증 앱으로 아래 QR 코드를 스캔하거나 키를 직접 입력한 후, 생성된 6자리 코드를 입력하세요.</div>
              <QrCode value={enroll.otpauthUri} />
              <div>
                <label className="label">수동 입력 키</label>
                <div className="mono" style={{ wordBreak: 'break-all', userSelect: 'all' }}>{enroll.secret}</div>
              </div>
              <div>
                <label className="label">인증 코드</label>
                <input
                  className="input mono"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="000000"
                  value={enrollCode}
                  onChange={(e) => setEnrollCode(sanitizeCode(e.target.value))}
                  style={{ width: 140, letterSpacing: '0.2em' }}
                />
              </div>
              <div className="row">
                <button className="btn" onClick={cancelEnroll} disabled={confirming}>
                  취소
                </button>
                <button
                  className="btn btn--primary"
                  onClick={() => void confirmEnroll()}
                  disabled={enrollCode.length !== 6 || confirming}
                >
                  확인
                </button>
              </div>
            </div>
          )}

          {/* ── ON: disable ─────────────────────────────────────────── */}
          {me.mfaEnabled && (
            <div className="row">
              <button className="btn btn--danger" onClick={openDisable} disabled={disabling}>
                2단계 인증 끄기
              </button>
            </div>
          )}
        </div>
      </div>

      <Dialog
        open={disableOpen}
        onClose={() => setDisableOpen(false)}
        title="2단계 인증 끄기"
        sub="확인을 위해 현재 인증 앱에 표시된 6자리 코드를 입력하세요."
        footer={
          <>
            <button className="btn" onClick={() => setDisableOpen(false)} disabled={disabling}>
              취소
            </button>
            <button
              className="btn btn--danger"
              onClick={() => void confirmDisable()}
              disabled={disableCode.length !== 6 || disabling}
            >
              끄기
            </button>
          </>
        }
      >
        <div>
          <label className="label">인증 코드</label>
          <input
            className="input mono"
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            value={disableCode}
            onChange={(e) => setDisableCode(sanitizeCode(e.target.value))}
            style={{ width: 140, letterSpacing: '0.2em' }}
          />
        </div>
      </Dialog>

      {recoveryCodes && (
        <RecoveryCodesModal codes={recoveryCodes} onClose={() => setRecoveryCodes(null)} />
      )}
    </div>
  );
}
