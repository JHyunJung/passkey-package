import { useState, useEffect, useRef, ReactNode } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';

export type IdleSessionModalProps = {
  onExtend: () => void;
  onLogout: () => void;
};

export function IdleSessionModal({ onExtend, onLogout }: IdleSessionModalProps): ReactNode {
  const [open, setOpen] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(60);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    function bumpIdle() {
      clearTimeout(idleTimer.current!);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(60);
        setOpen(true);
      }, 90 * 1000); // 90s for demo
    }
    bumpIdle();
    const handler = () => { if (!open) bumpIdle(); };
    ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current!);
      ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open]);

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
          보안을 위해 30분 동안 활동이 없으면 자동으로 로그아웃됩니다. 작업을 계속하려면 <strong>세션 연장</strong>을 눌러주세요.
        </p>
        <div style={{ height: 6, borderRadius: 4, background: "var(--surface-3)", overflow: "hidden" }}>
          <div style={{ width: `${(secondsLeft / 60) * 100}%`, height: "100%", background: secondsLeft > 20 ? "var(--accent)" : "var(--danger)", transition: "width 1s linear, background 220ms" }} />
        </div>
        <div className="muted" style={{ fontSize: 12 }}>
          모든 mutation은 audit log에 기록되어 있으므로 작업 내역은 보존됩니다.
        </div>
      </div>
    </Dialog>
  );
}
