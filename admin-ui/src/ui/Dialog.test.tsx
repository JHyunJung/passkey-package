import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRef } from 'react';
import { Dialog } from '@/ui';

describe('Dialog', () => {
  it('기본: Escape 와 스크림 클릭으로 닫힌다', () => {
    const onClose = vi.fn();
    render(<Dialog open onClose={onClose} title="제목"><button>a</button></Dialog>);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).toHaveBeenCalledTimes(2);
  });
  it('dismissible=false ignores Escape and scrim', () => {
    const onClose = vi.fn();
    render(<Dialog open onClose={onClose} title="시크릿" dismissible={false}><button>a</button></Dialog>);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).not.toHaveBeenCalled();
  });
  it('제목으로 라벨링되고 aria-modal', () => {
    render(<Dialog open onClose={() => {}} title="신규 Tenant 생성" />);
    const d = screen.getByRole('dialog', { name: '신규 Tenant 생성' });
    expect(d).toHaveAttribute('aria-modal', 'true');
  });
  it('role=alertdialog 지원', () => {
    render(<Dialog open onClose={() => {}} title="정지" role="alertdialog" />);
    expect(screen.getByRole('alertdialog', { name: '정지' })).toBeInTheDocument();
  });
  it('initialFocusRef 에 포커스', () => {
    function Host() {
      const ref = useRef<HTMLButtonElement>(null);
      return <Dialog open onClose={() => {}} title="t" initialFocusRef={ref} footer={<><button ref={ref}>취소</button><button>정지</button></>} />;
    }
    render(<Host />);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
  });
  it('Tab 이 다이얼로그 안에서 순환한다', () => {
    render(<Dialog open onClose={() => {}} title="t" footer={<><button>하나</button><button>둘</button></>} />);
    const two = screen.getByRole('button', { name: '둘' });
    two.focus();
    fireEvent.keyDown(two, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '하나' }));
  });
});
