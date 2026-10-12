import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRef, useState } from 'react';
import { useFocusTrap } from './useFocusTrap';

function Host({ withInitial }: { withInitial?: boolean }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const second = useRef<HTMLButtonElement>(null);
  const trap = useFocusTrap(open, box, withInitial ? second : undefined);
  return (
    <>
      <button onClick={() => setOpen(true)}>열기</button>
      {open && (
        <div ref={box} onKeyDown={trap}>
          <button>하나</button>
          <button ref={second}>둘</button>
          <button disabled>꺼짐</button>
          <button onClick={() => setOpen(false)}>닫기</button>
        </div>
      )}
    </>
  );
}

function openFrom() {
  const opener = screen.getByRole('button', { name: '열기' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

describe('useFocusTrap', () => {
  it('열리면 첫 요소, 닫히면 열기 전 위치로 포커스', () => {
    render(<Host />);
    const opener = openFrom();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(document.activeElement).toBe(opener);
  });
  it('initialFocusRef 가 있으면 그 요소로', () => {
    render(<Host withInitial />);
    openFrom();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '둘' }));
  });
  it('Tab 은 안에서 순환하고 비활성 요소를 건너뛴다', () => {
    render(<Host />);
    openFrom();
    const last = screen.getByRole('button', { name: '닫기' });
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
    fireEvent.keyDown(document.activeElement!, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
  });
  it('경계가 비활성·숨김·tabindex=-1·aria-hidden 이어도 실제 포커스 가능 요소 사이에서만 순환한다', () => {
    render(
      <div data-testid="box" onKeyDown={undefined}>
        <Bounds />
      </div>,
    );
    const a = screen.getByRole('button', { name: '처음' });
    const z = screen.getByRole('button', { name: '마지막' });
    z.focus();
    fireEvent.keyDown(z, { key: 'Tab' });
    expect(document.activeElement).toBe(a);
    fireEvent.keyDown(a, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(z);
  });
  it('포커스 가능 요소가 없으면 Tab 을 막아 컨테이너에 머문다', () => {
    render(<Empty />);
    const box = screen.getByTestId('empty');
    box.focus();
    const notPrevented = fireEvent.keyDown(box, { key: 'Tab' });
    expect(notPrevented).toBe(false);
    expect(document.activeElement).toBe(box);
  });
});

function Bounds() {
  const box = useRef<HTMLDivElement>(null);
  const trap = useFocusTrap(true, box);
  return (
    <div ref={box} onKeyDown={trap}>
      <button disabled>앞 비활성</button>
      <button tabIndex={-1}>앞 탭제외</button>
      <button>처음</button>
      <button>중간</button>
      <button>마지막</button>
      <div hidden><button>숨김</button></div>
      <div aria-hidden="true"><button>가림</button></div>
      <button tabIndex={-1}>뒤 탭제외</button>
      <button disabled>뒤 비활성</button>
    </div>
  );
}

function Empty() {
  const box = useRef<HTMLDivElement>(null);
  const trap = useFocusTrap(true, box);
  return <div ref={box} data-testid="empty" tabIndex={-1} onKeyDown={trap}><button disabled>꺼짐</button></div>;
}
