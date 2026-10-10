import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Menu, ConfirmByTyping, StatusStrip, Stepper, SecretBox } from '@/ui';

describe('Menu', () => {
  function setup() {
    const rotate = vi.fn(), revoke = vi.fn();
    render(<Menu label="키 액션" items={[{ label: '회전', onSelect: rotate }, { label: '접두사 복사', onSelect: vi.fn() }]} dangerItems={[{ label: '회수', onSelect: revoke, danger: true }]} />);
    fireEvent.click(screen.getByRole('button', { name: '키 액션' }));
    return { rotate, revoke };
  }
  it('열리면 첫 항목에 포커스, 위험 구획 표시', () => {
    setup();
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회전' }));
    expect(screen.getByText('위험')).toBeInTheDocument();
  });
  it('ArrowDown 순환 + Enter 선택 후 닫힘', () => {
    const { revoke } = setup();
    const menu = screen.getByRole('menu');
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회수' }));
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회전' }));
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    fireEvent.keyDown(document.activeElement!, { key: 'Enter' });
    expect(revoke).toHaveBeenCalled();
    expect(screen.queryByRole('menu')).toBeNull();
  });
  it('ArrowUp 을 컨테이너(인덱스 -1)에서 누르면 마지막 항목으로 이동', () => {
    setup();
    const menu = screen.getByRole('menu');
    menu.focus();
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '회수' }));
  });
  it('트리거에 포커스가 있을 때 Escape 로 메뉴가 닫힌다', () => {
    setup();
    const trigger = screen.getByRole('button', { name: '키 액션' });
    trigger.focus();
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
  it('Escape 로 닫히고 트리거로 포커스 복귀', () => {
    setup();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '키 액션' }));
  });
});

describe('Menu 클릭 전파', () => {
  it('항목·트리거 클릭이 상위 onClick(행 클릭 등) 으로 전파되지 않는다', () => {
    const spy = vi.fn();
    const onSelect = vi.fn();
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events -- 상위 클릭 전파 검증용 테스트 래퍼
    render(<div onClick={spy}><Menu label="키 액션" items={[{ label: '회전', onSelect }]} /></div>);
    fireEvent.click(screen.getByRole('button', { name: '키 액션' }));
    expect(spy).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    expect(onSelect).toHaveBeenCalled();
    expect(spy).not.toHaveBeenCalled();
  });
});

describe('Menu V2 트리거·위치', () => {
  function rect(top: number, right: number): DOMRect {
    return { top, bottom: top + 32, left: right - 32, right, width: 32, height: 32, x: right - 32, y: top, toJSON: () => ({}) } as DOMRect;
  }
  it('기본 트리거는 가로 ⋯ 아이콘 버튼, trigger="button" 이면 테두리 있는 .btn', () => {
    const { rerender } = render(<Menu label="더보기" items={[{ label: 'a', onSelect: vi.fn() }]} />);
    const icon = screen.getByRole('button', { name: '더보기' });
    expect(icon).toHaveClass('icon-btn');
    const cx = Array.from(icon.querySelectorAll('circle')).map((c) => c.getAttribute('cy'));
    expect(new Set(cx).size).toBe(1); // 세 점이 같은 y — 가로
    rerender(<Menu label="더보기" trigger="button" items={[{ label: 'a', onSelect: vi.fn() }]} />);
    expect(screen.getByRole('button', { name: '더보기' })).toHaveClass('btn');
  });
  it('열린 메뉴는 position:fixed 로 트리거 아래·오른쪽 정렬 — 스크롤 컨테이너(.tw)에 잘리지 않는다', () => {
    render(<Menu label="키 액션" items={[{ label: '회전', onSelect: vi.fn() }]} />);
    const trigger = screen.getByRole('button', { name: '키 액션' });
    trigger.getBoundingClientRect = () => rect(100, 500);
    fireEvent.click(trigger);
    const menu = screen.getByRole('menu');
    expect(menu.style.position).toBe('fixed');
    expect(menu.style.top).toBe('138px');
    expect(menu.style.right).toBe(`${window.innerWidth - 500}px`);
  });
  it('아래 공간이 모자라면 트리거 위로 연다', () => {
    render(<Menu label="키 액션" items={[{ label: '회전', onSelect: vi.fn() }]} />);
    const trigger = screen.getByRole('button', { name: '키 액션' });
    trigger.getBoundingClientRect = () => rect(window.innerHeight - 40, 500);
    fireEvent.click(trigger);
    const menu = screen.getByRole('menu');
    expect(menu.style.top).toBe('auto');
    expect(menu.style.bottom).toBe('46px');
  });
  it('페이지가 스크롤되면 닫힌다(고정 위치가 트리거에서 떨어지지 않게)', () => {
    render(<Menu label="키 액션" items={[{ label: '회전', onSelect: vi.fn() }]} />);
    fireEvent.click(screen.getByRole('button', { name: '키 액션' }));
    fireEvent.scroll(window);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});

describe('ConfirmByTyping', () => {
  it('actionLabel 이 있으면 안내 문구에 그 버튼 이름을 쓴다', () => {
    render(<ConfirmByTyping expected="acme" label="slug 입력" actionLabel="정지" onMatchChange={vi.fn()} id="c2" />);
    expect(screen.getByText('일치해야 정지 버튼이 활성화됩니다.')).toBeInTheDocument();
  });
  it('정확히 일치할 때만 true', () => {
    const onMatch = vi.fn();
    render(<ConfirmByTyping expected="acme" label="slug 입력" onMatchChange={onMatch} id="c" />);
    const input = screen.getByRole('textbox', { name: /slug 입력/ });
    fireEvent.change(input, { target: { value: 'acm' } });
    expect(onMatch).toHaveBeenLastCalledWith(false);
    fireEvent.change(input, { target: { value: 'acme' } });
    expect(onMatch).toHaveBeenLastCalledWith(true);
    fireEvent.change(input, { target: { value: 'acme ' } });
    expect(onMatch).toHaveBeenLastCalledWith(false);
  });
});

describe('StatusStrip / Stepper / SecretBox', () => {
  it('StatusStrip 은 요약 라벨을 가진 img', () => {
    const { container } = render(<StatusStrip results={[true, true, false, null]} label="최근 4회 중 1회 위변조" />);
    expect(screen.getByRole('img', { name: '최근 4회 중 1회 위변조' })).toBeInTheDocument();
    expect(container.querySelectorAll('.strip__bar--bad')).toHaveLength(1);
    expect(container.querySelectorAll('.strip__bar--none')).toHaveLength(1);
  });
  it('Stepper done 은 체크, now 는 aria-current', () => {
    render(<Stepper steps={[{ label: 'QR 스캔', state: 'done' }, { label: '코드 확인', state: 'now' }, { label: '복구 코드 저장', state: 'todo' }]} />);
    expect(screen.getByText('코드 확인').closest('li')).toHaveAttribute('aria-current', 'step');
  });
  it('SecretBox 값과 복사 버튼', () => {
    render(<SecretBox value="pk_live_x_secret" />);
    expect(screen.getByText('pk_live_x_secret')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /클립보드/ })).toBeInTheDocument();
  });
});
