import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Menu } from './Menu';

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
