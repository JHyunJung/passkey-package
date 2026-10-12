import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Menu, type MenuItem } from './Menu';

const REASON = '위변조 의심 tenant가 있으면 쓸 수 없습니다.';

function setup() {
  const onRun = vi.fn();
  const onOther = vi.fn();
  render(
    <Menu
      label="더 보기"
      trigger="button"
      items={[
        { label: '내보내기', onSelect: onOther },
        { label: '체인 백필…', onSelect: onRun, danger: true, disabled: true, disabledReason: REASON },
      ]}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
  return { onRun, onOther };
}

describe('Menu 비활성 항목', () => {
  it('aria-disabled 와 사유를 보이고, 클릭해도 실행·닫힘 없음', () => {
    const { onRun } = setup();
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).toHaveAttribute('title', REASON);
    expect(item).toHaveTextContent(REASON);
    fireEvent.click(item);
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('Enter 로도 실행되지 않는다', () => {
    const { onRun } = setup();
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    item.focus();
    fireEvent.keyDown(item, { key: 'Enter' });
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('활성 항목은 그대로 실행하고 닫힌다', () => {
    const { onOther } = setup();
    const item = screen.getByRole('menuitem', { name: '내보내기' });
    expect(item).not.toHaveAttribute('aria-disabled');
    fireEvent.click(item);
    expect(onOther).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});

describe('Menu 비활성 항목 — 키보드·사유 없음·열림 방향', () => {
  it('Space 로도 실행되지 않는다', () => {
    const { onRun } = setup();
    const item = screen.getByRole('menuitem', { name: /체인 백필…/ });
    item.focus();
    fireEvent.keyDown(item, { key: ' ' });
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('ArrowDown/ArrowUp 이 비활성 항목에 포커스를 준다(키보드 순서 유지)', () => {
    setup();
    const first = screen.getByRole('menuitem', { name: '내보내기' });
    const disabled = screen.getByRole('menuitem', { name: /체인 백필…/ });
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(disabled);
    fireEvent.keyDown(disabled, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(first);
  });

  it('disabledReason 이 없으면 사유 줄·title 없이, 여전히 실행되지 않는다', () => {
    const onRun = vi.fn();
    render(
      <Menu
        label="더 보기"
        trigger="button"
        items={[{ label: '잠김', onSelect: onRun, disabled: true }]}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    const item = screen.getByRole('menuitem', { name: '잠김' });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).not.toHaveAttribute('title');
    expect(item.querySelector('.hint')).toBeNull();
    fireEvent.click(item);
    fireEvent.keyDown(item, { key: 'Enter' });
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('사유 줄이 있으면 높이를 더 잡아, 36px/항목으로는 들어가지만 실제로는 넘칠 때 위로 연다', () => {
    const originalH = window.innerHeight;
    window.innerHeight = 296;
    try {
      render(
        <Menu
          label="더 보기"
          trigger="button"
          items={[
            { label: '내보내기', onSelect: vi.fn() },
            { label: '체인 백필…', onSelect: vi.fn(), disabled: true, disabledReason: REASON },
          ]}
        />,
      );
      const trigger = screen.getByRole('button', { name: '더 보기' });
      // 트리거 하단 200px: 36px/항목 추정은 200+6+90=296 로 딱 맞아 아래로 열린다.
      trigger.getBoundingClientRect = () => ({ top: 170, bottom: 200, left: 480, right: 500, width: 20, height: 30, x: 480, y: 170, toJSON: () => ({}) }) as DOMRect;
      fireEvent.click(trigger);
      const menu = screen.getByRole('menu');
      expect(menu.style.top).toBe('auto');
      expect(menu.style.bottom).not.toBe('');
    } finally {
      window.innerHeight = originalH;
    }
  });
});

describe('Menu 열림 방향 — 렌더된 높이로 보정', () => {
  // 트리거(.btn)와 메뉴(.menu)의 rect 만 흉내 낸다. 메뉴 높이는 실제 렌더 결과를 대신한다.
  // 메뉴의 자연 높이는 rect 와 scrollHeight(잘려 있어도 내용 전체 높이) 둘 다로 흉내 낸다.
  function mockRects(trigger: { top: number; bottom: number; right: number }, menuHeight: number) {
    const rect = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const isMenu = this.classList.contains('menu');
      const top = isMenu ? 0 : trigger.top;
      const bottom = isMenu ? menuHeight : trigger.bottom;
      const right = isMenu ? 0 : trigger.right;
      return { top, bottom, left: 0, right, width: 0, height: bottom - top, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
    });
    const scroll = vi.spyOn(Element.prototype, 'scrollHeight', 'get').mockImplementation(function (this: Element) {
      return this.classList.contains('menu') ? menuHeight : 0;
    });
    return { mockRestore: () => { rect.mockRestore(); scroll.mockRestore(); } };
  }

  const TWO_ITEMS: MenuItem[] = [
    { label: '내보내기', onSelect: vi.fn() },
    { label: '체인 백필…', onSelect: vi.fn(), disabled: true, disabledReason: REASON },
  ];

  function renderAndOpen(viewportH: number, trigger: { top: number; bottom: number; right: number }, menuHeight: number, items: MenuItem[] = TWO_ITEMS) {
    const originalH = window.innerHeight;
    window.innerHeight = viewportH;
    const spy = mockRects(trigger, menuHeight);
    render(<Menu label="더 보기" trigger="button" items={items} />);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    return { menu: screen.getByRole('menu'), restore: () => { window.innerHeight = originalH; spy.mockRestore(); } };
  }

  const SIX_ITEMS: MenuItem[] = Array.from({ length: 6 }, (_, i) => ({ label: `항목 ${i + 1}`, onSelect: vi.fn() }));

  it('추정으로는 아래에 들어가지만 렌더된 메뉴가 넘치면 위로 연다', () => {
    // 추정 100px: 300+6+100=406 ≤ 520 → 아래. 실제 240px: 300+6+240=546 > 520 → 넘침. 위는 270-6-240=24 ≥ 0 → 위로.
    const { menu, restore } = renderAndOpen(520, { top: 270, bottom: 300, right: 500 }, 240);
    try {
      expect(menu.style.top).toBe('auto');
      expect(menu.style.bottom).toBe('256px');
    } finally {
      restore();
    }
  });

  it('추정으로는 위로 열리지만 렌더된 메뉴가 실제로는 아래에 들어가면 아래로 연다', () => {
    // 추정 100px: 100+6+100=206 > 200 → 위. 실제 80px: 100+6+80=186 ≤ 200 → 아래.
    const { menu, restore } = renderAndOpen(200, { top: 70, bottom: 100, right: 500 }, 80);
    try {
      expect(menu.style.top).toBe('106px');
      expect(menu.style.bottom).toBe('');
    } finally {
      restore();
    }
  });

  it('어느 쪽에도 다 안 들어가면 아래 공간이 더 크면 아래로 열고, 남은 높이만큼 max-height 로 자르며 스크롤한다', () => {
    // 뷰포트 300, 트리거 70~100, 메뉴 240: 아래 194px, 위 64px. 위로 뒤집으면 화면 밖(-176px)으로 나간다.
    const { menu, restore } = renderAndOpen(300, { top: 70, bottom: 100, right: 500 }, 240);
    try {
      expect(menu.style.top).toBe('106px');
      expect(menu.style.bottom).toBe('');
      expect(menu.style.maxHeight).toBe('194px');
      expect(menu.style.overflowY).toBe('auto');
    } finally {
      restore();
    }
  });

  it('어느 쪽에도 다 안 들어가고 위 공간이 더 크면 위로 열고, 남은 높이만큼 max-height 로 자른다', () => {
    // 뷰포트 300, 트리거 200~230, 메뉴 240: 아래 64px, 위 194px.
    const { menu, restore } = renderAndOpen(300, { top: 200, bottom: 230, right: 500 }, 240);
    try {
      expect(menu.style.top).toBe('auto');
      expect(menu.style.bottom).toBe('106px');
      expect(menu.style.maxHeight).toBe('194px');
      expect(menu.style.overflowY).toBe('auto');
    } finally {
      restore();
    }
  });

  it('추정부터 이미 잘리는 경우, 실측 배치가 같은 max-height 여도 잘림이 유지된다', () => {
    // 뷰포트 300, 트리거 70~100: 아래 194, 위 64. 항목 6개 추정 228, 실측 240 → 둘 다 아래·194px.
    const { menu, restore } = renderAndOpen(300, { top: 70, bottom: 100, right: 500 }, 240, SIX_ITEMS);
    try {
      expect(menu.style.top).toBe('106px');
      expect(menu.style.maxHeight).toBe('194px');
      expect(menu.style.overflowY).toBe('auto');
    } finally {
      restore();
    }
  });

  it('렌더된 높이를 잴 수 없으면(0) 추정 배치의 max-height 를 그대로 둔다', () => {
    const { menu, restore } = renderAndOpen(300, { top: 70, bottom: 100, right: 500 }, 0, SIX_ITEMS);
    try {
      expect(menu.style.maxHeight).toBe('194px');
      expect(menu.style.overflowY).toBe('auto');
    } finally {
      restore();
    }
  });
});

describe('Menu 스크롤 닫힘', () => {
  function open() {
    render(<Menu label="더 보기" trigger="button" items={[{ label: '내보내기', onSelect: vi.fn() }]} />);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    return screen.getByRole('menu');
  }

  it('잘린 메뉴 안을 스크롤해도 닫히지 않는다', () => {
    const menu = open();
    fireEvent.scroll(menu);
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('페이지(문서)를 스크롤하면 닫힌다', () => {
    open();
    fireEvent.scroll(document);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('창을 스크롤해도 닫힌다', () => {
    open();
    fireEvent.scroll(window);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
