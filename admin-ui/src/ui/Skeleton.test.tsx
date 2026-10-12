import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { Skeleton } from './Skeleton';

afterEach(() => vi.useRealTimers());

describe('Skeleton', () => {
  it('status 역할과 aria-busy, 줄 수', () => {
    const { container } = render(<Skeleton rows={3} />);
    const s = screen.getByRole('status');
    expect(s).toHaveAttribute('aria-busy', 'true');
    expect(container.querySelectorAll('.skeleton-block__line')).toHaveLength(3);
  });
  it('3초가 지나면 "아직 불러오는 중입니다…"', () => {
    vi.useFakeTimers();
    render(<Skeleton />);
    expect(screen.queryByText('아직 불러오는 중입니다…')).toBeNull();
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText('아직 불러오는 중입니다…')).toBeInTheDocument();
  });
});
