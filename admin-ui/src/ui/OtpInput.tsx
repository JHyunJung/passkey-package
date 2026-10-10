import { useRef, ClipboardEvent, KeyboardEvent } from 'react';

type Props = { length?: number; value: string; onChange: (v: string) => void; onComplete?: (v: string) => void; disabled?: boolean; ariaLabel?: string; autoFocus?: boolean };

export function OtpInput({ length = 6, value, onChange, onComplete, disabled, ariaLabel = '인증 코드', autoFocus }: Props) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = Array.from({ length }, (_, i) => value[i] ?? '');

  function commit(next: string, focusIndex: number) {
    const v = next.replace(/\D/g, '').slice(0, length);
    onChange(v);
    refs.current[Math.min(focusIndex, length - 1)]?.focus();
    if (v.length === length) onComplete?.(v);
  }
  function setAt(i: number, d: string) {
    const arr = digits.slice();
    arr[i] = d;
    // value 는 연속 숫자열로 유지된다(빈 칸은 join 시 사라져 앞으로 당겨진다)
    return arr.join('');
  }
  function onInput(i: number, raw: string) {
    const d = raw.replace(/\D/g, '').slice(-1);
    if (!d) return;
    commit(setAt(i, d), i + 1);
  }
  function onPaste(e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault();
    const only = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, length);
    if (!only) return;
    commit(only, only.length);
  }
  function onKeyDown(i: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && !digits[i] && i > 0) {
      e.preventDefault();
      onChange(digits.slice(0, i - 1).join(''));
      refs.current[i - 1]?.focus();
    } else if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus();
    else if (e.key === 'ArrowRight' && i < length - 1) refs.current[i + 1]?.focus();
  }
  return (
    <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
      <legend className="sr-only">{ariaLabel} {length}자리</legend>
      <div className="otp">
        {digits.map((d, i) => (
          <input key={i} ref={(el) => { refs.current[i] = el; }} className="otp__cell" inputMode="numeric" autoComplete={i === 0 ? 'one-time-code' : 'off'}
            aria-label={`${ariaLabel} ${i + 1}번째 자리`} value={d} disabled={disabled} autoFocus={autoFocus && i === 0} maxLength={1}
            onChange={(e) => onInput(i, e.target.value)} onPaste={onPaste} onKeyDown={(e) => onKeyDown(i, e)} />
        ))}
      </div>
    </fieldset>
  );
}
