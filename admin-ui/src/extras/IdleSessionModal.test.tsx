import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { IdleSessionModal } from './IdleSessionModal';

describe('IdleSessionModal', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('shows the warning after (idleMinutes*60 - 60) seconds of inactivity', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    // 2분 = 120s, 경고 리드타임 60s → 60s 후 모달.
    act(() => { vi.advanceTimersByTime(59 * 1000); });
    expect(screen.queryByText(/세션이 곧 만료됩니다/)).toBeNull();
    act(() => { vi.advanceTimersByTime(2 * 1000); }); // 61s 총
    expect(screen.getByText(/세션이 곧 만료됩니다/)).toBeInTheDocument();
  });

  it('renders the configured minutes in the body copy, not a hardcoded 30', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(61 * 1000); });
    expect(screen.getByText(/보안을 위해 2분 동안/)).toBeInTheDocument();
    expect(screen.queryByText(/30분 동안/)).toBeNull();
  });

  it('calls onExtend when 세션 연장 is clicked', () => {
    const onExtend = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(61 * 1000); });
    act(() => { screen.getByRole('button', { name: '세션 연장' }).click(); });
    expect(onExtend).toHaveBeenCalledTimes(1);
  });

  it('logs out at the full idle time for a 1-minute policy (warn 10s + countdown 50s = 60s)', () => {
    const onLogout = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={1} onExtend={vi.fn()} onLogout={onLogout} />);
    // 1분 정책: total=60s, warnAfter=max(60-60,10)=10s, countdown=max(60-10,1)=50s.
    act(() => { vi.advanceTimersByTime(10 * 1000); }); // 모달 표시 시점
    expect(screen.getByText(/세션이 곧 만료됩니다/)).toBeInTheDocument();
    expect(onLogout).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(50 * 1000); }); // 카운트다운 종료 → 총 60s
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('calls onLogout exactly once on expiry', () => {
    const onLogout = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={onLogout} />);
    // 2분 정책: warnAfter=60s, countdown=60s.
    act(() => { vi.advanceTimersByTime(60 * 1000); }); // 모달 표시
    expect(onLogout).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(60 * 1000); }); // 카운트다운 종료
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
});
