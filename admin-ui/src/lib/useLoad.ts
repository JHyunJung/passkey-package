import { useCallback, useEffect, useRef, useState } from 'react';

export type LoadStatus = 'loading' | 'ok' | 'failed';
export type LoadState<T> = { status: LoadStatus; data: T | undefined; error: unknown; reloading: boolean; reload: () => void };

/** 비동기 로드를 loading/ok/failed 로 추적한다. 재시도 중에는 이전 data 를 유지하고, 늦게 끝난 이전 요청은 버린다. */
export function useLoad<T>(fetcher: () => Promise<T>, deps: unknown[]): LoadState<T> {
  const [status, setStatus] = useState<LoadStatus>('loading');
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [reloading, setReloading] = useState(false);
  const seq = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const run = useCallback((isReload: boolean) => {
    const my = ++seq.current;
    if (isReload) setReloading(true); else setStatus('loading');
    const onOk = (v: T) => { if (my !== seq.current) return; setData(v); setError(undefined); setStatus('ok'); setReloading(false); };
    const onFail = (e: unknown) => { if (my !== seq.current) return; setError(e); setStatus('failed'); setReloading(false); };
    let p: Promise<T>;
    try { p = fetcherRef.current(); } catch (e) { onFail(e); return; }
    p.then(onOk, onFail);
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { run(false); }, deps);

  const reload = useCallback(() => run(true), [run]);
  return { status, data, error, reloading, reload };
}
