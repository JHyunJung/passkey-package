import * as React from 'react';
import { toast as sonnerToast } from 'sonner';

export type ToastInput = {
  kind: 'ok' | 'err' | 'warn';
  title: string;
  message?: string;
  traceId?: string;
  duration?: number;
};

// sonner default duration: 4000ms (previously 4200ms in the legacy Context impl —
// 200ms shortening is intentional and matches sonner's host-controlled timing).
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

/**
 * @deprecated no-op shim. sonner `<Toaster />` (AppProviders) 가 단일 host.
 * 외부 진입점/테스트 setup 이 직접 import 하던 경우를 위한 호환 레이어.
 * Phase A 안정화 후 제거 예정.
 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
