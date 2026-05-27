import * as React from 'react';
import { toast as sonnerToast } from 'sonner';

export type ToastInput = {
  kind: 'ok' | 'err' | 'warn';
  title: string;
  message?: string;
  traceId?: string;
  duration?: number;
};

function show({ kind, title, message, traceId, duration }: ToastInput) {
  const descriptionParts = [message, traceId ? `traceId: ${traceId}` : null].filter(Boolean) as string[];
  const description = descriptionParts.length > 0 ? descriptionParts.join('\n') : undefined;
  const opts: Parameters<typeof sonnerToast.success>[1] = {
    ...(description ? { description } : {}),
    ...(duration !== undefined ? { duration } : {}),
  };
  if (kind === 'ok') return sonnerToast.success(title, opts);
  if (kind === 'err') return sonnerToast.error(title, opts);
  return sonnerToast.warning(title, opts);
}

export function useToast() {
  return show;
}

// Backwards-compat shim — 기존 App.tsx 의 <ToastProvider> wrap 호환용 no-op.
// sonner Toaster 가 AppProviders 안에서 단일 host 역할.
export function ToastProvider({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
