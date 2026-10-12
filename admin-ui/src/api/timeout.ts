import { RequestTimeoutError } from './types';

/** 기본 요청 시간 제한. 멈춘 요청이 폴링·로그아웃을 영원히 붙잡지 않게 한다. */
export const DEFAULT_TIMEOUT_MS = 30_000;
/** 체인 검증·백필·MDS 동기화처럼 서버 작업이 긴 호출용. */
export const LONG_TIMEOUT_MS = 120_000;

export type RequestOptions = { timeoutMs?: number };

/**
 * run 전체(응답 헤더 + 본문 읽기)를 timeoutMs 안에 끝내지 못하면 fetch 를 abort 하고
 * RequestTimeoutError 로 reject 한다. signal 을 무시하는 fetch 여도 시간이 되면 reject 한다.
 */
export async function withTimeout<T>(timeoutMs: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const ctrl = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { ctrl.abort(); reject(new RequestTimeoutError(timeoutMs)); }, timeoutMs);
  });
  try {
    return await Promise.race([run(ctrl.signal), timedOut]);
  } finally {
    clearTimeout(timer);
  }
}
