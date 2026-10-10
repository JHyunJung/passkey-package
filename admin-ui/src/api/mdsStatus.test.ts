import { describe, it, expect, vi } from 'vitest';

vi.mock('./client', () => ({ api: { get: vi.fn().mockResolvedValue([]), post: vi.fn() } }));

import { api } from './client';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT } from './mdsStatus';

describe('mdsStatusApi.history', () => {
  it('limit 을 쿼리로 보낸다 (서버 상한 50, 최신순)', async () => {
    await mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT);
    expect(api.get).toHaveBeenCalledWith('/admin/api/mds/history?limit=30');
  });
  it('스트립 길이 상수는 30', () => {
    expect(MDS_HISTORY_STRIP_LIMIT).toBe(30);
  });
});
