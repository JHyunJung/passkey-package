import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ConfirmDialog } from './ConfirmDialog';
import { ApiError } from '@/api/types';

const base = { open: true, onClose: vi.fn(), title: '김운영 님을 정지할까요?', verb: '정지' };

describe('ConfirmDialog', () => {
  it('alertdialog, 기본 포커스는 취소, 확인 버튼 라벨은 verb', () => {
    render(<ConfirmDialog {...base} level="L1" onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
    expect(screen.getByRole('button', { name: '정지' })).toBeEnabled();
  });

  it('성공하면 onConfirm 한 번 + onClose', async () => {
    const onClose = vi.fn();
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<ConfirmDialog {...base} onClose={onClose} level="L1" onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('처리 중에는 버튼이 "정지 중…"으로 비활성, 연타해도 한 번만 호출', async () => {
    let resolve!: () => void;
    const onConfirm = vi.fn(() => new Promise<void>((r) => { resolve = r; }));
    render(<ConfirmDialog {...base} level="L1" onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    const busy = screen.getByRole('button', { name: '정지 중…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    resolve();
  });

  it('실패하면 다이얼로그 안 인라인 오류, 닫히지 않음', async () => {
    const onClose = vi.fn();
    render(<ConfirmDialog {...base} onClose={onClose} level="L1" onConfirm={vi.fn().mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'))} />);
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('정지하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '정지' })).toBeEnabled();
  });

  it('L3 입력 확인: 일치해야 버튼이 켜진다', () => {
    render(<ConfirmDialog {...base} level="L3" verb="정지" typeToConfirm={{ expected: 'acme', label: 'slug 를 입력하세요' }} onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    const btn = screen.getByRole('button', { name: '정지' });
    expect(btn).toBeDisabled();
    fireEvent.change(screen.getByLabelText('slug 를 입력하세요'), { target: { value: 'acme' } });
    expect(btn).toBeEnabled();
  });

  it('L3 체크 확인: 체크해야 버튼이 켜진다', () => {
    render(<ConfirmDialog {...base} level="L3" verb="저장" acknowledge="모든 등록이 차단된다는 것을 이해했습니다" onConfirm={vi.fn().mockResolvedValue(undefined)} />);
    const btn = screen.getByRole('button', { name: '저장' });
    expect(btn).toBeDisabled();
    fireEvent.click(screen.getByLabelText('모든 등록이 차단된다는 것을 이해했습니다'));
    expect(btn).toBeEnabled();
  });
});
