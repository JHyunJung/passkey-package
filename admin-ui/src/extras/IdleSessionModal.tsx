import { useState, useEffect, useRef, ReactNode } from 'react';
import { Alert, Button, Dialog } from '@/ui';
import { describeError, type ErrorInfo } from '@/lib/describeError';

export type IdleSessionModalProps = {
  idleTimeoutMinutes: number;
  /** 실패하면 reject — 모달이 열린 채 오류를 인라인으로 보여 준다. */
  onExtend: () => void | Promise<void>;
  onLogout: () => void;
};

const WARN_LEAD_SECONDS = 60;
/** 연장 요청이 진행 중이면 만료 로그아웃을 이만큼까지만 미룬다. */
const EXTEND_GRACE_MS = 10_000;

/** 스크린리더에는 매초가 아니라 30초·10초 남았을 때만 알린다. */
function announcement(secondsLeft: number): string {
  if (secondsLeft <= 10) return '세션 만료까지 10초 남았습니다.';
  if (secondsLeft <= 30) return '세션 만료까지 30초 남았습니다.';
  return '';
}

export function IdleSessionModal({ idleTimeoutMinutes, onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  // 카운트다운 길이를 전체 idle 시간에서 파생한다.
  // 짧은 정책(예: 1분)에서도 (모달 표시 시점 + 카운트다운)이 전체 idle과 정확히 일치하도록.
  const totalSeconds = idleTimeoutMinutes * 60;
  const warnAfterSeconds = Math.max(totalSeconds - WARN_LEAD_SECONDS, 10);
  const countdownSeconds = Math.max(totalSeconds - warnAfterSeconds, 1);

  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(countdownSeconds);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const extendRef = useRef<HTMLButtonElement>(null);
  const [extending, setExtending] = useState(false);
  const extendingRef = useRef(false);
  const [extendError, setExtendError] = useState<ErrorInfo | null>(null);
  const mounted = useRef(true);
  const expiredRef = useRef(false); // 카운트다운이 0 에 도달함
  const loggedOutRef = useRef(false); // 만료 로그아웃이 이미 발화함
  const graceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; clearTimeout(graceTimer.current!); };
  }, []);

  function expireLogout() {
    if (loggedOutRef.current) return;
    loggedOutRef.current = true;
    clearTimeout(graceTimer.current!);
    graceTimer.current = null;
    setOpen(false);
    onLogout?.();
  }

  async function extend() {
    if (extendingRef.current) return; // 연타로 중복 호출 방지
    extendingRef.current = true;
    setExtending(true);
    setExtendError(null);
    try {
      await onExtend();
      if (mounted.current && !loggedOutRef.current) {
        clearTimeout(graceTimer.current!);
        graceTimer.current = null;
        setOpen(false);
      }
    } catch (e) {
      if (!mounted.current || loggedOutRef.current) return;
      // 요청 도중 이미 만료됐다면 실패한 연장은 로그아웃으로 이어진다.
      if (expiredRef.current) { expireLogout(); return; }
      setExtendError(describeError(e));
    } finally {
      extendingRef.current = false;
      if (mounted.current) setExtending(false);
    }
  }

  useEffect(() => {
    // 모달이 이미 열려 있는 동안에는 idle 타이머를 다시 무장하지 않는다.
    // (그렇지 않으면 warn 타이머가 카운트다운 도중 다시 발화해 secondsLeft가 리셋됨.)
    if (open) return;
    const warnAfterMs = warnAfterSeconds * 1000;
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(countdownSeconds);
        setExtendError(null);
        expiredRef.current = false;
        loggedOutRef.current = false;
        setOpen(true);
      }, warnAfterMs);
    }
    bumpIdle();
    const handler = () => bumpIdle();
    ['mousemove', 'keydown', 'click', 'scroll'].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ['mousemove', 'keydown', 'click', 'scroll'].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open, warnAfterSeconds, countdownSeconds]);

  useEffect(() => {
    if (!open) return;
    const tick = setInterval(() => {
      setSecondsLeft((s) => (s <= 1 ? 0 : s - 1));
    }, 1000);
    return () => clearInterval(tick);
  }, [open]);

  // 카운트다운이 0에 도달하면 로그아웃 (부작용은 updater 밖, 렌더 후 effect에서 1회만).
  useEffect(() => {
    if (!open || secondsLeft !== 0) return;
    expiredRef.current = true;
    if (extendingRef.current) {
      // 연장 요청이 진행 중: 결과를 기다리되 유예 시간이 지나면 로그아웃.
      if (!graceTimer.current) graceTimer.current = setTimeout(expireLogout, EXTEND_GRACE_MS);
      return;
    }
    expireLogout();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, secondsLeft, onLogout]);

  // 연장 실패 후 버튼이 다시 활성화된 뒤에 포커스를 되돌린다(비활성 버튼은 포커스를 받지 못함).
  useEffect(() => {
    if (extendError && !extending) extendRef.current?.focus();
  }, [extendError, extending]);

  if (!open) return null;
  const pct = secondsLeft / countdownSeconds;
  const C = 2 * Math.PI * 28;
  const tone = secondsLeft > 20 ? 'var(--warn-dot)' : 'var(--bad)';
  return (
    // 기본 포커스 = "세션 연장": 실수로 Enter 를 눌러도 작업이 사라지지 않는다.
    <Dialog open={open} onClose={() => {}} dismissible={false} role="alertdialog" initialFocusRef={extendRef}
      headerIcon={
          <span style={{ position: 'relative', width: 64, height: 64, flex: 'none' }}>
            <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true">
              <circle cx="32" cy="32" r="28" fill="none" stroke="var(--line-soft)" strokeWidth="6" />
              <circle cx="32" cy="32" r="28" fill="none" stroke={tone} strokeWidth="6" strokeLinecap="round"
                strokeDasharray={`${C} ${C}`} strokeDashoffset={C * (1 - pct)} transform="rotate(-90 32 32)" style={{ transition: 'stroke-dashoffset 1s linear' }} />
            </svg>
            <span className="mono" aria-hidden="true" style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 600 }}>{secondsLeft}초</span>
          </span>
      }
      title="세션이 곧 만료됩니다"
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <Button onClick={() => { setOpen(false); onLogout(); }}>로그아웃</Button>
        <Button ref={extendRef} variant="primary" onClick={extend} disabled={extending}>세션 연장</Button>
      </>}
    >
      <div className="stack-3">
        <div
          className="idle-progress"
          role="progressbar"
          aria-label="세션 만료까지 남은 시간"
          aria-valuemin={0}
          aria-valuemax={countdownSeconds}
          aria-valuenow={secondsLeft}
          aria-valuetext={`${secondsLeft}초 남음`}
        >
          <span className="idle-progress__bar" style={{ width: `${pct * 100}%`, background: tone }} />
        </div>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)', lineHeight: 1.6 }}>
          보안 정책에 따라 {idleTimeoutMinutes}분 동안 활동이 없어 세션을 종료하려고 합니다. 작업 중이라면 세션을 연장하세요.
        </p>
        <p style={{ margin: 0, fontSize: 13, lineHeight: 1.6 }}>
          <strong>로그아웃하면 저장하지 않은 변경이 사라집니다.</strong>
        </p>
        {extendError && <Alert tone="bad" title={extendError.title}>{extendError.detail}</Alert>}
        <span className="sr-only" aria-live="polite">{announcement(secondsLeft)}</span>
      </div>
    </Dialog>
  );
}
