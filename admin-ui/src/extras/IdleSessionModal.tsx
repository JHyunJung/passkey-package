import { useState, useEffect, useRef, ReactNode } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';

export type IdleSessionModalProps = {
  idleTimeoutMinutes: number;
  onExtend: () => void;
  onLogout: () => void;
};

const WARN_LEAD_SECONDS = 60;

export function IdleSessionModal({ idleTimeoutMinutes, onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(WARN_LEAD_SECONDS);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // 경고는 전체 idle 시간에서 카운트다운 리드타임(60s)을 뺀 시점에 뜬다.
    // 정책이 1분처럼 짧아도 음수가 되지 않도록 최소 10초를 보장한다.
    const warnAfterMs = Math.max(idleTimeoutMinutes * 60 - WARN_LEAD_SECONDS, 10) * 1000;
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(WARN_LEAD_SECONDS);
        setOpen(true);
      }, warnAfterMs);
    }
    bumpIdle();
    const handler = () => { if (!open) bumpIdle(); };
    ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open, idleTimeoutMinutes]);

  useEffect(() => {
    if (!open) return;
    const tick = setInterval(() => {
      setSecondsLeft((s) => {
        if (s <= 1) { clearInterval(tick); setOpen(false); onLogout?.(); return 0; }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(tick);
  }, [open, onLogout]);

  if (!open) return null;
  return (
    <Dialog open={open} onClose={() => {}} closeOnScrim={false}
      title={<span style={{ display: "flex", alignItems: "center", gap: 8 }}><Icons.Lock size={18} /> 세션이 곧 만료됩니다</span>}
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <button className="btn" onClick={() => { setOpen(false); onLogout(); }}>지금 로그아웃</button>
        <button className="btn btn--primary" onClick={() => { setOpen(false); onExtend(); }}>세션 연장</button>
      </>}
    >
      <div className="stack-3">
        <p style={{ margin: 0, fontSize: 13, color: "var(--text-soft)", lineHeight: 1.6 }}>
          보안을 위해 {idleTimeoutMinutes}분 동안 활동이 없으면 자동으로 로그아웃됩니다. 작업을 계속하려면 <strong>세션 연장</strong>을 눌러주세요.
        </p>
        <div style={{ height: 6, borderRadius: 4, background: "var(--surface-3)", overflow: "hidden" }}>
          <div style={{ width: `${(secondsLeft / WARN_LEAD_SECONDS) * 100}%`, height: "100%", background: secondsLeft > 20 ? "var(--accent)" : "var(--danger)", transition: "width 1s linear, background 220ms" }} />
        </div>
        <div className="muted" style={{ fontSize: 12 }}>
          모든 mutation은 audit log에 기록되어 있으므로 작업 내역은 보존됩니다.
        </div>
      </div>
    </Dialog>
  );
}
