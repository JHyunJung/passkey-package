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
});
