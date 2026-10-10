import { useState, createContext, useContext, useCallback, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type ToastInput = {
  kind?: 'ok' | 'err' | 'warn';
  title?: string;
  message?: string;
  traceId?: string;
  duration?: number;
};

type ToastItem = ToastInput & { id: string };

const ToastCtx = createContext<((t: ToastInput) => void) | null>(null);

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const push = useCallback((t: ToastInput) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
    setTimeout(() => setToasts((arr) => arr.filter((x) => x.id !== id)), t.duration || 4200);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => (
          <div key={t.id} className="toast" role="status">
            <div className={`toast__icon toast__icon--${t.kind || 'ok'}`}>
              {t.kind === 'err' ? <Icons.X size={11} /> : t.kind === 'warn' ? <Icons.Alert size={11} /> : <Icons.Check size={11} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="toast__title">{t.title}</div>
              {t.message && <div className="toast__sub">{t.message}</div>}
              {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
            </div>
            <button className="btn btn--ghost btn--xs" onClick={() => setToasts((a) => a.filter((x) => x.id !== t.id))} aria-label="닫기"><Icons.X size={12} /></button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used inside <ToastHost>');
  return ctx;
}
