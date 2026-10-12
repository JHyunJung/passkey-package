import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ToastHost, useToast, ToastInput } from './ToastHost';

afterEach(() => vi.useRealTimers());

function Push({ t }: { t: ToastInput }) {
  const toast = useToast();
  return <button onClick={() => toast(t)}>push</button>;
}
const setup = (t: ToastInput) => { render(<ToastHost><Push t={t} /></ToastHost>); fireEvent.click(screen.getByText('push')); };

describe('ToastHost', () => {
  it('오류 토스트는 role=alert 이고 시간이 지나도 남는다', () => {
    vi.useFakeTimers();
    setup({ kind: 'err', title: '저장하지 못했습니다' });
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했습니다');
    act(() => { vi.advanceTimersByTime(60000); });
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('성공 토스트는 5초 뒤 사라지지만 hover 중에는 남는다', () => {
    vi.useFakeTimers();
    setup({ kind: 'ok', title: '저장했습니다' });
    const t = screen.getByRole('status');
    fireEvent.mouseEnter(t);
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.getByText('저장했습니다')).toBeInTheDocument();
    fireEvent.mouseLeave(t);
    act(() => { vi.advanceTimersByTime(5000); });
    expect(screen.queryByText('저장했습니다')).toBeNull();
  });

  it('action 버튼을 누르면 onClick 후 닫힌다', () => {
    const onClick = vi.fn();
    setup({ kind: 'ok', title: '정지했습니다', action: { label: '되돌리기', onClick } });
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('정지했습니다')).toBeNull();
  });
});
