import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { Check, X, Alert } from './Icons';

type ToastKind = 'ok' | 'err' | 'warn';

interface Toast {
  id: string;
  kind: ToastKind;
  title: string;
  message?: string;
  traceId?: string;
  duration?: number;
}

type PushFn = (t: Omit<Toast, 'id'>) => void;

const ToastCtx = createContext<PushFn | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push: PushFn = useCallback((t) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
    setTimeout(() => setToasts((arr) => arr.filter((x) => x.id !== id)), t.duration ?? 4200);
  }, []);

  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => (
          <div key={t.id} className="toast" role="status">
            <div className={`toast__icon toast__icon--${t.kind}`}>
              {t.kind === 'err' ? <X size={11} /> : t.kind === 'warn' ? <Alert size={11} /> : <Check size={11} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="toast__title">{t.title}</div>
              {t.message && <div className="toast__sub">{t.message}</div>}
              {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
            </div>
            <button
              className="btn btn--ghost btn--xs"
              onClick={() => setToasts((a) => a.filter((x) => x.id !== t.id))}
              aria-label="닫기"
            >
              <X size={12} />
            </button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast(): PushFn {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}
