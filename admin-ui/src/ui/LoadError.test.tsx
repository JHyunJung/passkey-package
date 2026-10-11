import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { LoadError } from './LoadError';
import { ApiError } from '@/api/types';

describe('LoadError', () => {
  it('role=alert 로 무엇을 못 불러왔는지 말하고 다시 시도를 호출한다', () => {
    const onRetry = vi.fn();
    render(<LoadError what="Credential 목록" error={new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-1')} onRetry={onRetry} />);
    const box = screen.getByRole('alert');
    expect(box).toHaveTextContent('Credential 목록을 불러오지 못했습니다');
    expect(box).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(box).toHaveTextContent('HTTP 503');
    expect(box).toHaveTextContent('tr-1');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
  it('받침 없는 대상은 "를"', () => {
    render(<LoadError what="시스템 정보" error={new Error('x')} onRetry={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent('시스템 정보를 불러오지 못했습니다');
  });
  it('retrying 중에는 버튼이 비활성 + "다시 시도 중…"', () => {
    render(<LoadError what="목록" error={new Error('x')} onRetry={() => {}} retrying />);
    expect(screen.getByRole('button', { name: '다시 시도 중…' })).toBeDisabled();
  });
});
