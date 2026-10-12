import { useCallback, useEffect, useRef, useState } from 'react';

export type LoadStatus = 'loading' | 'ok' | 'failed';
export type LoadState<T> = { status: LoadStatus; data: T | undefined; error: unknown; reloading: boolean; reload: () => Promise<void> };

function sameDeps(a: readonly unknown[] | null, b: readonly unknown[]): boolean {
  return a !== null && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
}

/**
 * 비동기 로드를 loading/ok/failed 로 추적한다. 재시도(reload) 중에는 이전 data 를 유지하고, 늦게 끝난 이전 요청은 버린다.
 * deps 가 바뀌면 이전 deps 의 data·error 를 보이지 않는다 — 바뀐 직후의 렌더부터 loading 이다.
 */
export function useLoad<T>(fetcher: () => Promise<T>, deps: unknown[]): LoadState<T> {
  const [status, setStatus] = useState<LoadStatus>('loading');
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [reloading, setReloading] = useState(false);
  const seq = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  /** 마지막으로 시작한(재시도가 아닌) 로드의 deps. */
  const runDeps = useRef<readonly unknown[] | null>(null);

  const run = useCallback((isReload: boolean): Promise<void> => {
    const my = ++seq.current;
    if (isReload) setReloading(true);
    else { setStatus('loading'); setData(undefined); setError(undefined); setReloading(false); }
    const onOk = (v: T) => { if (my !== seq.current) return; setData(v); setError(undefined); setStatus('ok'); setReloading(false); };
    const onFail = (e: unknown) => { if (my !== seq.current) return; setError(e); setStatus('failed'); setReloading(false); };
    let p: Promise<T>;
    try { p = fetcherRef.current(); } catch (e) { onFail(e); return Promise.resolve(); }
    return p.then(onOk, onFail);
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { runDeps.current = deps; void run(false); }, deps);

  const reload = useCallback(() => run(true), [run]);
  // deps 가 바뀐 렌더에서는 effect 가 새 로드를 시작하기 전이라도 이전 결과를 내보내지 않는다.
  if (!sameDeps(runDeps.current, deps)) return { status: 'loading', data: undefined, error: undefined, reloading: false, reload };
  return { status, data, error, reloading, reload };
}
