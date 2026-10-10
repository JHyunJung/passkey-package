import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Badge, StatusBadge, Button, IconButton, KpiCard, PropChip, Alert, EmptyState } from '@/ui';

describe('ui basics', () => {
  it('Badge tone 이 기존 클래스 체계로 매핑된다', () => {
    render(<><Badge tone="ok">활성</Badge><Badge tone="bad">정지</Badge><Badge tone="plat" dot={false}>PLATFORM</Badge></>);
    expect(screen.getByText('활성')).toHaveClass('badge', 'badge--success', 'badge--dot');
    expect(screen.getByText('정지')).toHaveClass('badge--danger');
    expect(screen.getByText('PLATFORM')).toHaveClass('badge--violet');
    expect(screen.getByText('PLATFORM')).not.toHaveClass('badge--dot');
  });
  it('StatusBadge 가 상태코드를 한국어 라벨로 보여준다', () => {
    render(<StatusBadge status="ACTIVE" />);
    expect(screen.getByText('활성')).toHaveClass('badge--success');
  });
  it('Button 은 기본 type=button, variant 클래스', () => {
    render(<Button variant="danger-solid">회수</Button>);
    const b = screen.getByRole('button', { name: '회수' });
    expect(b).toHaveAttribute('type', 'button');
    expect(b).toHaveClass('btn', 'btn--danger-solid');
  });
  it('IconButton 은 label 을 aria-label 로 노출', () => {
    render(<IconButton label="더보기" icon={<span />} />);
    expect(screen.getByRole('button', { name: '더보기' })).toBeInTheDocument();
  });
  it('KpiCard onClick 이 있으면 aria-pressed 버튼', () => {
    const onClick = vi.fn();
    render(<KpiCard label="활성 Tenant" value="3" selected onClick={onClick} />);
    const b = screen.getByRole('button', { name: /활성 Tenant/ });
    expect(b).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(b);
    expect(onClick).toHaveBeenCalled();
  });
  it('KpiCard onClick 이 없으면 버튼이 아니다', () => {
    render(<KpiCard label="등록 Credential" value="10" />);
    expect(screen.queryByRole('button')).toBeNull();
  });
  it('PropChip 긴 값은 말줄임 클래스 + title 로 전체값 제공', () => {
    const long = 'passkey.'.repeat(10) + 'example.com';
    render(<PropChip label="RP ID" value={long} copy />);
    const v = screen.getByText(long);
    expect(v).toHaveClass('propchip__v');
    expect(v).toHaveAttribute('title', long);
    expect(screen.getByRole('button', { name: 'RP ID 복사' })).toBeInTheDocument();
  });
  it('Alert bad 는 role=alert', () => {
    render(<Alert tone="bad" title="위변조 의심">본문</Alert>);
    expect(screen.getByRole('alert')).toHaveTextContent('위변조 의심');
  });
  it('EmptyState 일러스트는 장식(alt="")', () => {
    const { container } = render(<EmptyState illustration="/x.webp" title="없음" />);
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
  });
});
