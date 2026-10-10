import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { OtpInput } from '@/ui';

function Host({ onComplete }: { onComplete: (v: string) => void }) {
  const [v, setV] = useState('');
  return <OtpInput value={v} onChange={setV} onComplete={onComplete} />;
}
const cells = () => screen.getAllByRole('textbox');

describe('OtpInput', () => {
  it('6칸을 렌더한다', () => {
    render(<Host onComplete={vi.fn()} />);
    expect(cells()).toHaveLength(6);
    expect(screen.getByRole('textbox', { name: '인증 코드 1번째 자리' })).toBeInTheDocument();
  });
  it('한 자리씩 입력하면 다음 칸으로 이동하고 6자리에서 onComplete', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    '123456'.split('').forEach((d, i) => fireEvent.change(cells()[i], { target: { value: d } }));
    expect(onComplete).toHaveBeenCalledWith('123456');
    expect(onComplete).toHaveBeenCalledTimes(1);
  });
  it('숫자가 아닌 입력은 무시', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.change(cells()[0], { target: { value: 'a' } });
    expect(cells()[0]).toHaveValue('');
  });
  it('paste with spaces/letters: 숫자만 남겨 앞에서부터 채우고 6자리면 완료', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.paste(cells()[0], { clipboardData: { getData: () => '12a 34-56789' } });
    expect(cells().map((c) => (c as HTMLInputElement).value).join('')).toBe('123456');
    expect(onComplete).toHaveBeenCalledWith('123456');
  });
  it('6자리 미만 붙여넣기는 완료하지 않는다', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.paste(cells()[0], { clipboardData: { getData: () => '123 4' } });
    expect(onComplete).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(cells()[4]);
  });
  it('빈 칸에서 Backspace 는 이전 칸을 지우고 포커스 이동', () => {
    render(<Host onComplete={vi.fn()} />);
    fireEvent.change(cells()[0], { target: { value: '1' } });
    fireEvent.change(cells()[1], { target: { value: '2' } });
    fireEvent.keyDown(cells()[2], { key: 'Backspace' });
    expect(cells()[1]).toHaveValue('');
    expect(document.activeElement).toBe(cells()[1]);
  });
});

describe('OtpInput 수정 라운드', () => {
  it('filled 칸 Backspace 는 그 칸을 비우고 포커스를 유지한다', () => {
    render(<Host onComplete={vi.fn()} />);
    fireEvent.change(cells()[0], { target: { value: '1' } });
    fireEvent.change(cells()[1], { target: { value: '2' } });
    fireEvent.change(cells()[1], { target: { value: '' } });
    expect(cells()[1]).toHaveValue('');
    expect(cells()[0]).toHaveValue('1');
    expect(document.activeElement).toBe(cells()[1]);
  });
  it('빈 값에서 칸 3 에 입력하면 0번 칸에 저장하고 1번 칸으로 포커스', () => {
    render(<Host onComplete={vi.fn()} />);
    fireEvent.change(cells()[3], { target: { value: '5' } });
    expect(cells()[0]).toHaveValue('5');
    expect(cells()[3]).toHaveValue('');
    expect(document.activeElement).toBe(cells()[1]);
  });
  it("'123 456' 붙여넣기는 onComplete 를 정확히 한 번 호출한다", () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.paste(cells()[0], { clipboardData: { getData: () => '123 456' } });
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith('123456');
  });
  it('0번 칸에 여러 자리가 들어오면 칸들을 채운다', () => {
    const onComplete = vi.fn();
    render(<Host onComplete={onComplete} />);
    fireEvent.change(cells()[0], { target: { value: '12' } });
    expect(cells().map((c) => (c as HTMLInputElement).value).join('')).toBe('12');
    fireEvent.change(cells()[0], { target: { value: '123456' } });
    expect(cells().map((c) => (c as HTMLInputElement).value).join('')).toBe('123456');
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith('123456');
  });
});
