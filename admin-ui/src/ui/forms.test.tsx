import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { Switch, Segmented, ChoiceCard, FilterChip, ChipInput, Field } from '@/ui';

describe('forms', () => {
  it('Switch 토글', () => {
    const onChange = vi.fn();
    render(<Switch checked={false} onChange={onChange} label="MFA 필수" />);
    fireEvent.click(screen.getByRole('switch', { name: 'MFA 필수' }));
    expect(onChange).toHaveBeenCalledWith(true);
  });
  it('Segmented aria-pressed 와 onChange', () => {
    const onChange = vi.fn();
    render(<Segmented ariaLabel="기간" value="7d" onChange={onChange} options={[{ value: '24h', label: '24h' }, { value: '7d', label: '7d' }]} />);
    expect(screen.getByRole('button', { name: '7d' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '24h' }));
    expect(onChange).toHaveBeenCalledWith('24h');
  });
  it('ChoiceCard role=radio', () => {
    const onSelect = vi.fn();
    render(<div role="radiogroup" aria-label="mode"><ChoiceCard checked={false} onSelect={onSelect} title="허용 목록" /></div>);
    fireEvent.click(screen.getByRole('radio', { name: /허용 목록/ }));
    expect(onSelect).toHaveBeenCalled();
  });
  it('FilterChip aria-pressed', () => {
    render(<FilterChip active onClick={() => {}}>AUTH_*</FilterChip>);
    expect(screen.getByRole('button', { name: 'AUTH_*' })).toHaveAttribute('aria-pressed', 'true');
  });
  it('ChipInput: Enter 로 추가, 중복·빈값 무시, validate 오류는 추가하지 않고 표시', () => {
    function Host() {
      const [v, setV] = useState<string[]>(['https://a.com']);
      return <ChipInput values={v} onChange={setV} placeholder="origin" ariaLabel="origin 추가" validate={(s) => (s.startsWith('https://') ? null : 'https:// 로 시작해야 합니다')} />;
    }
    render(<Host />);
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: '  https://b.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://b.com')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'https://a.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://a.com')).toHaveLength(1);
    fireEvent.change(input, { target: { value: 'http://c.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.queryByText('http://c.com')).toBeNull();
    expect(screen.getByText('https:// 로 시작해야 합니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'https://a.com 삭제' }));
    expect(screen.queryByText('https://a.com')).toBeNull();
  });
  it('Field 오류 표시', () => {
    render(<Field label="이메일" htmlFor="em" error="필수"><input id="em" /></Field>);
    expect(screen.getByText('필수')).toHaveClass('field__error');
  });
});
