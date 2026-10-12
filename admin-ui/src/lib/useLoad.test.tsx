import { describe, it, expect, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useLoad } from './useLoad';

function deferred<T>() {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useLoad', () => {
  it('처음에는 loading, 성공하면 ok + data', async () => {
    const { result } = renderHook(() => useLoad(() => Promise.resolve(3), []));
    expect(result.current.status).toBe('loading');
    await waitFor(() => expect(result.current.status).toBe('ok'));
    expect(result.current.data).toBe(3);
  });

  it('실패하면 failed + error', async () => {
    const err = new Error('boom');
    const { result } = renderHook(() => useLoad(() => Promise.reject(err), []));
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.error).toBe(err);
  });

  it('reload 중에는 이전 data 를 유지하고 reloading=true', async () => {
    let n = 0;
    const d = deferred<number>();
    const fetcher = vi.fn(() => (n++ === 0 ? Promise.resolve(1) : d.promise));
    const { result } = renderHook(() => useLoad(fetcher, []));
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => { void result.current.reload(); });
    expect(result.current.status).toBe('ok');
    expect(result.current.data).toBe(1);
    expect(result.current.reloading).toBe(true);
    await act(async () => { d.resolve(2); });
    expect(result.current.data).toBe(2);
    expect(result.current.reloading).toBe(false);
  });

  it('재시도가 실패하면 failed 로 바뀌지만 이전 data 는 남는다', async () => {
    let n = 0;
    const fetcher = () => (n++ === 0 ? Promise.resolve(1) : Promise.reject(new Error('x')));
    const { result } = renderHook(() => useLoad(fetcher, []));
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => { void result.current.reload(); });
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.data).toBe(1);
  });

  it('fetcher 가 동기적으로 던지면 failed 가 되고 error 가 남는다 (최초 로드)', async () => {
    const err = new Error('sync');
    const { result } = renderHook(() => useLoad((): Promise<number> => { throw err; }, []));
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.error).toBe(err);
    expect(result.current.reloading).toBe(false);
  });

  it('reload 중 fetcher 가 동기적으로 던지면 failed 이고 reloading 은 false 로 돌아온다', async () => {
    let n = 0;
    const err = new Error('sync-reload');
    const fetcher = (): Promise<number> => {
      if (n++ === 0) return Promise.resolve(1);
      throw err;
    };
    const { result } = renderHook(() => useLoad(fetcher, []));
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => { void result.current.reload(); });
    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.error).toBe(err);
    expect(result.current.data).toBe(1);
    expect(result.current.reloading).toBe(false);
  });

  it('deps 가 바뀐 뒤 늦게 끝난 이전 요청은 무시한다', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const { result, rerender } = renderHook(({ id }) => useLoad(() => (id === 'a' ? first.promise : second.promise), [id]), { initialProps: { id: 'a' } });
    rerender({ id: 'b' });
    await act(async () => { second.resolve('B'); });
    await act(async () => { first.resolve('A'); });
    expect(result.current.data).toBe('B');
  });

  it('deps 가 바뀐 직후의 첫 렌더는 이전 deps 의 data·error 가 아니라 loading 이다', async () => {
    const renders: { id: string; status: string; data: unknown }[] = [];
    const b = deferred<string>();
    const { result, rerender } = renderHook(({ id }) => {
      const s = useLoad(() => (id === 'a' ? Promise.resolve('A') : b.promise), [id]);
      renders.push({ id, status: s.status, data: s.data });
      return s;
    }, { initialProps: { id: 'a' } });
    await waitFor(() => expect(result.current.data).toBe('A'));
    renders.length = 0;
    rerender({ id: 'b' });
    expect(renders[0]).toEqual({ id: 'b', status: 'loading', data: undefined });
    await act(async () => { await Promise.resolve(); });
    expect(renders.every((r) => r.status === 'loading' && r.data === undefined)).toBe(true);
    await act(async () => { b.resolve('B'); });
    expect(result.current).toMatchObject({ status: 'ok', data: 'B' });
  });

  it('deps 가 바뀐 직후 첫 렌더는 이전 deps 의 실패도 보이지 않는다', async () => {
    const renders: { status: string; error: unknown }[] = [];
    const { result, rerender } = renderHook(({ id }) => {
      const s = useLoad(() => (id === 'a' ? Promise.reject(new Error('A 실패')) : Promise.resolve('B')), [id]);
      renders.push({ status: s.status, error: s.error });
      return s;
    }, { initialProps: { id: 'a' } });
    await waitFor(() => expect(result.current.status).toBe('failed'));
    renders.length = 0;
    rerender({ id: 'b' });
    expect(renders[0]).toEqual({ status: 'loading', error: undefined });
    await waitFor(() => expect(result.current.data).toBe('B'));
  });

  it('같은 deps 의 reload 는 계속 이전 data 를 보여 준다(deps 비교가 reload 를 loading 으로 바꾸지 않는다)', async () => {
    let n = 0;
    const d = deferred<number>();
    const { result, rerender } = renderHook(({ id }) => useLoad(() => (n++ === 0 ? Promise.resolve(1) : d.promise), [id]), { initialProps: { id: 'a' } });
    await waitFor(() => expect(result.current.data).toBe(1));
    act(() => { void result.current.reload(); });
    rerender({ id: 'a' });
    expect(result.current.status).toBe('ok');
    expect(result.current.data).toBe(1);
    await act(async () => { d.resolve(2); });
    expect(result.current.data).toBe(2);
  });
});
