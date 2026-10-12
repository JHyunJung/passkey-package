import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
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
    expect(screen.getByText(/보안 정책에 따라 2분 동안/)).toBeInTheDocument();
    expect(screen.queryByText(/30분 동안/)).toBeNull();
  });

  it('calls onExtend when 세션 연장 is clicked', async () => {
    const onExtend = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(61 * 1000); });
    await act(async () => { screen.getByRole('button', { name: '세션 연장' }).click(); });
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
  it('기본 포커스는 "세션 연장"(Enter = 연장), 저장하지 않은 변경 손실 경고, 내부 용어 없음', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '세션 연장' }));
    expect(screen.getByText('로그아웃하면 저장하지 않은 변경이 사라집니다.')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).not.toHaveTextContent(/mutation|audit log/);
  });

  it('카운트다운 진행 막대(progressbar)가 남은 초를 반영한다', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    const bar = screen.getByRole('progressbar', { name: '세션 만료까지 남은 시간' });
    expect(bar).toHaveAttribute('aria-valuemax', '60');
    expect(bar).toHaveAttribute('aria-valuenow', '60');
    act(() => { vi.advanceTimersByTime(10 * 1000); });
    expect(bar).toHaveAttribute('aria-valuenow', '50');
    expect(screen.getByText('50초')).toBeInTheDocument();
  });

  it('스크린리더 안내는 매초가 아니라 30초·10초 남았을 때만', () => {
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    const live = document.querySelector('[aria-live="polite"]')!;
    expect(live.textContent).toBe('');
    act(() => { vi.advanceTimersByTime(25 * 1000); }); // 35s 남음
    expect(live.textContent).toBe('');
    act(() => { vi.advanceTimersByTime(5 * 1000); });
    expect(live).toHaveTextContent('세션 만료까지 30초 남았습니다.');
    act(() => { vi.advanceTimersByTime(5 * 1000); }); // 25s 남음 — 변화 없음
    expect(live.textContent).toBe('세션 만료까지 30초 남았습니다.');
    act(() => { vi.advanceTimersByTime(10 * 1000); }); // 15s 남음 — 변화 없음
    expect(live.textContent).toBe('세션 만료까지 30초 남았습니다.');
    act(() => { vi.advanceTimersByTime(5 * 1000); }); // 10s 남음
    expect(live).toHaveTextContent('세션 만료까지 10초 남았습니다.');
  });

  it('"로그아웃" 버튼은 즉시 onLogout', () => {
    const onLogout = vi.fn();
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={vi.fn()} onLogout={onLogout} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('"세션 연장" 실패는 모달 안에 오류로 표시되고 모달은 열린 채 유지, 연타해도 한 번만 호출', async () => {
    let rejectExtend!: (e: unknown) => void;
    const onExtend = vi.fn(() => new Promise<void>((_, rej) => { rejectExtend = rej; }));
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    const btn = screen.getByRole('button', { name: '세션 연장' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(onExtend).toHaveBeenCalledTimes(1);
    act(() => { screen.getByRole('button', { name: '로그아웃' }).focus(); }); // 포커스가 이미 다른 곳에 있음
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '로그아웃' }));
    await act(async () => { rejectExtend(new TypeError('x')); });
    expect(screen.getByRole('alert')).toHaveTextContent('네트워크에 연결할 수 없습니다');
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '세션 연장' })).not.toBeDisabled();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '세션 연장' }));
  });

  it('"세션 연장" 성공하면 모달이 닫힌다', async () => {
    const onExtend = vi.fn(() => Promise.resolve());
    render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={vi.fn()} />);
    act(() => { vi.advanceTimersByTime(60 * 1000); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '세션 연장' })); });
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  describe('만료 임박 시 연장 요청 진행 중', () => {
    function setup() {
      let resolve!: () => void; let reject!: (e: unknown) => void;
      const onExtend = vi.fn(() => new Promise<void>((res, rej) => { resolve = res; reject = rej; }));
      const onLogout = vi.fn();
      render(<IdleSessionModal idleTimeoutMinutes={2} onExtend={onExtend} onLogout={onLogout} />);
      act(() => { vi.advanceTimersByTime(60 * 1000); }); // 모달 표시, 60s 남음
      act(() => { vi.advanceTimersByTime(59 * 1000); }); // 1s 남음
      fireEvent.click(screen.getByRole('button', { name: '세션 연장' }));
      return { onLogout, resolve: () => resolve(), reject: (e: unknown) => reject(e) };
    }

    it('만료 후 3초에 성공하면 로그아웃하지 않고 모달만 닫는다', async () => {
      const { onLogout, resolve } = setup();
      act(() => { vi.advanceTimersByTime(3000); });
      expect(onLogout).not.toHaveBeenCalled();
      await act(async () => { resolve(); });
      expect(onLogout).not.toHaveBeenCalled();
      expect(screen.queryByRole('alertdialog')).toBeNull();
      act(() => { vi.advanceTimersByTime(20_000); });
      expect(onLogout).not.toHaveBeenCalled();
    });

    it('만료 후 3초에 실패하면 로그아웃한다', async () => {
      const { onLogout, reject } = setup();
      act(() => { vi.advanceTimersByTime(3000); });
      expect(onLogout).not.toHaveBeenCalled();
      await act(async () => { reject(new TypeError('x')); });
      expect(onLogout).toHaveBeenCalledTimes(1);
    });

    it('유예 10초 안에 끝나지 않으면 만료+10초에 로그아웃한다', async () => {
      const { onLogout, resolve } = setup();
      act(() => { vi.advanceTimersByTime(1000); }); // 만료
      act(() => { vi.advanceTimersByTime(9999); });
      expect(onLogout).not.toHaveBeenCalled();
      act(() => { vi.advanceTimersByTime(1); });
      expect(onLogout).toHaveBeenCalledTimes(1);
      await act(async () => { resolve(); });
      expect(onLogout).toHaveBeenCalledTimes(1);
    });

    it('유예 중 수동 로그아웃하면 onLogout 은 한 번만 — 유예 만료·연장 실패에도 중복 호출 없음', async () => {
      const { onLogout, reject } = setup();
      act(() => { vi.advanceTimersByTime(1000); }); // 만료, 유예 시작
      fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
      expect(onLogout).toHaveBeenCalledTimes(1);
      act(() => { vi.advanceTimersByTime(11_000); }); // 유예 경과
      await act(async () => { reject(new TypeError('x')); });
      expect(onLogout).toHaveBeenCalledTimes(1);
    });
  });
});
