import { useState, useEffect, useRef, ReactNode } from 'react';
import { Dialog } from '@/ui';

export type IdleSessionModalProps = {
  idleTimeoutMinutes: number;
  onExtend: () => void;
  onLogout: () => void;
};

const WARN_LEAD_SECONDS = 60;

export function IdleSessionModal({ idleTimeoutMinutes, onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  // 카운트다운 길이를 전체 idle 시간에서 파생한다.
  // 짧은 정책(예: 1분)에서도 (모달 표시 시점 + 카운트다운)이 전체 idle과 정확히 일치하도록.
  const totalSeconds = idleTimeoutMinutes * 60;
  const warnAfterSeconds = Math.max(totalSeconds - WARN_LEAD_SECONDS, 10);
  const countdownSeconds = Math.max(totalSeconds - warnAfterSeconds, 1);

  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(countdownSeconds);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // 모달이 이미 열려 있는 동안에는 idle 타이머를 다시 무장하지 않는다.
    // (그렇지 않으면 warn 타이머가 카운트다운 도중 다시 발화해 secondsLeft가 리셋됨.)
    if (open) return;
    const warnAfterMs = warnAfterSeconds * 1000;
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(countdownSeconds);
        setOpen(true);
      }, warnAfterMs);
    }
    bumpIdle();
    const handler = () => bumpIdle();
    ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.removeEventListener(e, handler));
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
    if (open && secondsLeft === 0) {
      setOpen(false);
      onLogout?.();
    }
  }, [open, secondsLeft, onLogout]);

  if (!open) return null;
  const pct = secondsLeft / countdownSeconds;
  const C = 2 * Math.PI * 28;
  return (
    <Dialog open={open} onClose={() => {}} dismissible={false} role="alertdialog"
      headerIcon={
          <span style={{ position: "relative", width: 64, height: 64, flex: "none" }}>
            <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true">
              <circle cx="32" cy="32" r="28" fill="none" stroke="var(--line-soft)" strokeWidth="6" />
              <circle cx="32" cy="32" r="28" fill="none" stroke={secondsLeft > 20 ? "var(--warn-dot)" : "var(--bad)"} strokeWidth="6" strokeLinecap="round"
                strokeDasharray={`${C} ${C}`} strokeDashoffset={C * (1 - pct)} transform="rotate(-90 32 32)" style={{ transition: "stroke-dashoffset 1s linear" }} />
            </svg>
            <span className="mono" style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 15, fontWeight: 600 }}>{secondsLeft}s</span>
          </span>
      }
      title="세션이 곧 만료됩니다"
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <button className="btn" onClick={() => { setOpen(false); onLogout(); }}>지금 로그아웃</button>
        <button className="btn btn--primary" onClick={() => { setOpen(false); onExtend(); }}>세션 연장</button>
      </>}
    >
      <div className="stack-3">
        <p style={{ margin: 0, fontSize: 13, color: "var(--ink-2)", lineHeight: 1.6 }}>
          보안을 위해 {idleTimeoutMinutes}분 동안 활동이 없으면 자동으로 로그아웃됩니다. 작업을 계속하려면 <strong>세션 연장</strong>을 눌러주세요.
        </p>
        <div className="muted" style={{ fontSize: 12 }}>
          모든 mutation은 audit log에 기록되어 있으므로 작업 내역은 보존됩니다.
        </div>
      </div>
    </Dialog>
  );
}
