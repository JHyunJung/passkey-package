import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, cleanup } from '@testing-library/react';

const get = vi.hoisted(() => vi.fn());
vi.mock('@/api/tenants', () => ({ tenantsApi: { get } }));

import { useCrumbTenant } from './useCrumbTenant';

afterEach(cleanup);

describe('useCrumbTenant — 브레드크럼용 테넌트 이름', () => {
  beforeEach(() => { get.mockReset(); });

  it('테넌트 경로면 id 로 이름을 불러온다(V2: Tenants › {이름} › {탭})', async () => {
    get.mockResolvedValue({ id: 't1', name: 'Demo RP' });
    const { result } = renderHook(() => useCrumbTenant('t1'));
    await waitFor(() => expect(result.current).toEqual({ id: 't1', name: 'Demo RP' }));
    expect(get).toHaveBeenCalledWith('t1');
  });

  it('테넌트 경로가 아니면 부르지 않고 null', () => {
    const { result } = renderHook(() => useCrumbTenant(null));
    expect(result.current).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });

  it('다른 테넌트로 옮기면 이전 이름을 쓰지 않는다', async () => {
    get.mockResolvedValueOnce({ id: 't1', name: 'Demo RP' });
    const { result, rerender } = renderHook(({ id }) => useCrumbTenant(id), { initialProps: { id: 't1' as string | null } });
    await waitFor(() => expect(result.current?.name).toBe('Demo RP'));
    get.mockReturnValueOnce(new Promise(() => {}));
    rerender({ id: 't2' });
    expect(result.current).toBeNull();
  });

  it('조회 실패는 이름 없이(null) — 브레드크럼은 Tenants 만', async () => {
    get.mockImplementation(() => Promise.reject(new Error('403')));
    const { result } = renderHook(() => useCrumbTenant('t1'));
    await new Promise((r) => setTimeout(r, 20));
    expect(get).toHaveBeenCalledTimes(1);
    expect(result.current).toBeNull();
  });
});
