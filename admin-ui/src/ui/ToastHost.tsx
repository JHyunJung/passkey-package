import { useState, createContext, useContext, useCallback, useEffect, useRef, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type ToastInput = {
  kind?: 'ok' | 'err' | 'warn';
  title?: string;
  message?: string;
  traceId?: string;
  /** 오류 원문(코드·서버 메시지 등). 본문에는 쉬운 문구만 두고 원문·traceId 는 접힌 "자세히" 안에만 보인다. */
  detail?: string;
  duration?: number;
  action?: { label: string; onClick: () => void };
};

type ToastItem = ToastInput & { id: string };

const ToastCtx = createContext<((t: ToastInput) => void) | null>(null);

function Toast({ t, onClose }: { t: ToastItem; onClose: () => void }) {
  const sticky = t.kind === 'err';
  const [hover, setHover] = useState(false);
  const [focusWithin, setFocusWithin] = useState(false);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    // 마우스가 올라가 있거나 키보드 포커스가 토스트 안에 있으면 자동 닫힘을 멈춘다.
    if (sticky || hover || focusWithin) return;
    const h = setTimeout(() => closeRef.current(), t.duration ?? 5000);
    return () => clearTimeout(h);
  }, [sticky, hover, focusWithin, t.duration]);
  return (
    <div className="toast" role={sticky ? 'alert' : 'status'} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      onFocus={() => setFocusWithin(true)}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocusWithin(false); }}>
      <div className={`toast__icon toast__icon--${t.kind || 'ok'}`}>
        {t.kind === 'err' ? <Icons.X size={11} /> : t.kind === 'warn' ? <Icons.Alert size={11} /> : <Icons.Check size={11} />}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="toast__title">{t.title}</div>
        {t.message && <div className="toast__sub">{t.message}</div>}
        {(t.detail || t.traceId) && (
          // 원문(코드·서버 메시지)과 traceId 는 지원 문의용 — 본문이 아니라 접힌 "자세히" 안에만 둔다.
          <details className="toast__details">
            <summary>자세히</summary>
            {t.detail && <div className="toast__trace">{t.detail}</div>}
            {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
          </details>
        )}
      </div>
      {t.action && (
        <button type="button" className="btn btn--sm toast__action" onClick={() => { t.action!.onClick(); onClose(); }}>{t.action.label}</button>
      )}
      <button type="button" className="btn btn--ghost btn--xs" onClick={onClose} aria-label="닫기"><Icons.X size={12} /></button>
    </div>
  );
}

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const push = useCallback((t: ToastInput) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
  }, []);
  const close = (id: string) => setToasts((arr) => arr.filter((x) => x.id !== id));
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => <Toast key={t.id} t={t} onClose={() => close(t.id)} />)}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used inside <ToastHost>');
  return ctx;
}
