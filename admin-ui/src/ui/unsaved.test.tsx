import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent, act, render } from '@testing-library/react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { SaveBar, useUnsavedGuard } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';

function Page() {
  const [dirty, setDirty] = useState(false);
  const guard = useUnsavedGuard(dirty);
  const nav = useNavigate();
  return (
    <div>
      <button onClick={() => setDirty(true)}>편집</button>
      <button onClick={() => nav('/tenants/1?tab=aaguid')}>탭 이동</button>
      <button onClick={() => nav('/activity')}>다른 화면</button>
      {guard}
    </div>
  );
}

describe('useUnsavedGuard', () => {
  it('dirty 가 아니면 바로 이동', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    await act(async () => { fireEvent.click(screen.getByText('다른 화면')); });
    expect(router.state.location.pathname).toBe('/activity');
  });
  it('blocks search-param-only navigation 그리고 머무르기', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    fireEvent.click(screen.getByText('편집'));
    await act(async () => { fireEvent.click(screen.getByText('탭 이동')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=webauthn');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '머무르기' }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
  });
  it('이동 을 누르면 진행', async () => {
    const { router } = renderWithDataRouter(<Page />, { initialPath: '/tenants/1?tab=webauthn' });
    fireEvent.click(screen.getByText('편집'));
    await act(async () => { fireEvent.click(screen.getByText('다른 화면')); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '이동' })); });
    expect(router.state.location.pathname).toBe('/activity');
  });
  it('dirty 면 beforeunload 를 막는다', () => {
    renderWithDataRouter(<Page />);
    fireEvent.click(screen.getByText('편집'));
    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });
});

describe('SaveBar', () => {
  it('count 0 이면 렌더하지 않음', () => {
    const { container } = render(<SaveBar count={0} onRevert={() => {}} onSave={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
  it('건수와 버튼', () => {
    const onSave = vi.fn(), onRevert = vi.fn();
    render(<SaveBar count={2} summary="timeoutMs · origins" onRevert={onRevert} onSave={onSave} saveLabel="저장…" />);
    expect(screen.getByRole('region', { name: '저장되지 않은 변경' })).toHaveTextContent('저장하지 않은 변경 2건');
    fireEvent.click(screen.getByRole('button', { name: '저장…' }));
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(onSave).toHaveBeenCalled();
    expect(onRevert).toHaveBeenCalled();
  });
});
