import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { LoadError } from './LoadError';
import { ApiError } from '@/api/types';
import { copyToClipboard } from '@/lib/clipboard';

vi.mock('@/lib/clipboard', () => ({ copyToClipboard: vi.fn() }));
const copyMock = vi.mocked(copyToClipboard);

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
  it('오류 정보 복사는 대상·제목·상세·HTTP·trace 를 담아 복사한다', async () => {
    copyMock.mockResolvedValue(true);
    render(<LoadError what="Credential 목록" error={new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-1')} onRetry={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '오류 정보 복사' }));
    await waitFor(() => expect(copyMock).toHaveBeenCalledTimes(1));
    const text = copyMock.mock.calls[0][0];
    expect(text).toContain('Credential 목록');
    expect(text).toContain('서버가 잠시 응답하지 않습니다');
    expect(text).toContain('저장된 데이터는 그대로 있습니다');
    expect(text).toContain('HTTP 503');
    expect(text).toContain('tr-1');
  });
  it('복사 성공 시 "복사했습니다" 를 버튼과 aria-live 영역에 표시한다', async () => {
    copyMock.mockResolvedValue(true);
    render(<LoadError what="목록" error={new Error('x')} onRetry={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '오류 정보 복사' }));
    expect(await screen.findByRole('button', { name: '복사했습니다' })).toBeInTheDocument();
    expect(screen.getByText('복사했습니다', { selector: '[aria-live="polite"]' })).toBeInTheDocument();
  });
  it('복사 실패 시 "복사하지 못했습니다" 를 표시한다', async () => {
    copyMock.mockResolvedValue(false);
    render(<LoadError what="목록" error={new Error('x')} onRetry={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '오류 정보 복사' }));
    expect(await screen.findByRole('button', { name: '복사하지 못했습니다' })).toBeInTheDocument();
    expect(screen.getByText('복사하지 못했습니다', { selector: '[aria-live="polite"]' })).toBeInTheDocument();
  });
});
