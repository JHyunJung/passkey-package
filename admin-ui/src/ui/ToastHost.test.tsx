import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
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

  it('포커스가 토스트 안에 있는 동안에는 자동으로 닫히지 않고, 포커스가 나가면 다시 5초 뒤 닫힌다', () => {
    vi.useFakeTimers();
    setup({ kind: 'ok', title: '정지했습니다', action: { label: '되돌리기', onClick: () => {} } });
    const undo = screen.getByRole('button', { name: '되돌리기' });
    act(() => { undo.focus(); });
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.getByText('정지했습니다')).toBeInTheDocument();
    // 토스트 안에서 포커스를 옮겨도 계속 멈춰 있다.
    act(() => { screen.getByRole('button', { name: '닫기' }).focus(); });
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.getByText('정지했습니다')).toBeInTheDocument();
    act(() => { (document.activeElement as HTMLElement).blur(); });
    act(() => { vi.advanceTimersByTime(5000); });
    expect(screen.queryByText('정지했습니다')).toBeNull();
  });

  it('오류 원문(detail)은 본문이 아니라 접힌 "자세히" 안에만 보인다', () => {
    setup({ kind: 'err', title: '저장하지 못했습니다', message: '요청을 처리하지 못했습니다', detail: 'HTTP 400 · [C001] rpId must not be blank' });
    const alert = screen.getByRole('alert');
    const details = alert.querySelector('details');
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute('open');
    expect(within(details as HTMLElement).getByText('자세히')).toBeInTheDocument();
    expect(details).toHaveTextContent('[C001] rpId must not be blank');
    // 본문(제목·설명)에는 원문이 없다.
    expect(alert.querySelector('.toast__title')).not.toHaveTextContent('C001');
    expect(alert.querySelector('.toast__sub')).not.toHaveTextContent('C001');
  });

  it('detail 이 없으면 "자세히"도 없다', () => {
    setup({ kind: 'err', title: '저장하지 못했습니다' });
    expect(screen.getByRole('alert').querySelector('details')).toBeNull();
  });

  it('traceId 도 원문과 함께 접힌 "자세히" 안에만 보인다(본문에 없음)', () => {
    setup({ kind: 'err', title: '저장하지 못했습니다', message: '서버가 잠시 응답하지 않습니다', detail: 'HTTP 503 · [C999] HTTP 503', traceId: 'tr-42' });
    const alert = screen.getByRole('alert');
    const details = alert.querySelector('details:not([open])') as HTMLElement;
    expect(details).toHaveTextContent('traceId · tr-42');
    const outside = Array.from(alert.querySelectorAll('*')).filter((el) => !details.contains(el) && el.textContent?.includes('tr-42') && el.children.length === 0);
    expect(outside).toHaveLength(0);
  });

  it('detail 없이 traceId 만 있어도 "자세히" 안에 둔다', () => {
    setup({ kind: 'err', title: 'RP를 전환하지 못했습니다', traceId: 'tr-7' });
    expect(screen.getByRole('alert').querySelector('details')).toHaveTextContent('traceId · tr-7');
  });
});
