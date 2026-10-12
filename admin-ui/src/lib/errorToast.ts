import { ApiError } from '@/api/types';
import type { ToastInput } from '@/ui/ToastHost';
import { describeError } from './describeError';

/** 오류의 원문(상태·코드·서버 메시지). 토스트의 "자세히"·지원 문의용 — 본문에는 쓰지 않는다. */
export function rawErrorDetail(e: unknown): string | undefined {
  if (e instanceof ApiError) return `HTTP ${e.httpStatus} · [${e.code}] ${e.serverMessage}`;
  if (e instanceof Error) return e.message || undefined;
  return e == null ? undefined : String(e);
}

/**
 * 오류 토스트 입력. 제목은 무엇이 실패했는지, 본문은 describeError 의 쉬운 문구,
 * 원문은 detail("자세히")에만 담는다. describeError 의 detail 이 서버 원문이면 본문에 넣지 않는다.
 */
export function errorToast(e: unknown, title: string, message?: string): ToastInput {
  const info = describeError(e);
  const raw = rawErrorDetail(e);
  const rawText = e instanceof ApiError ? e.serverMessage : e instanceof Error ? e.message : String(e);
  const friendlyDetail = info.detail && info.detail !== rawText ? info.detail : undefined;
  return {
    kind: 'err',
    title,
    message: message ?? (friendlyDetail ? `${info.title}. ${friendlyDetail}` : info.title),
    detail: raw,
    traceId: info.traceId,
  };
}
