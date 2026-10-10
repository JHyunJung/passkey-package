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
  // 한 자리 입력: value 는 연속 숫자열이므로 빈 칸 앞이면 value 끝에 붙인다(위치 = min(i, value.length)).
  function onInput(i: number, raw: string) {
    const typed = raw.replace(/\D/g, '');
    if (typed.length === 0) {
      // filled 칸에서 Backspace: 이 칸과 뒤 값을 비우고 같은 칸에 포커스를 유지한다.
      onChange(value.slice(0, i));
      refs.current[i]?.focus();
      return;
    }
    if (typed.length >= 2) {
      // SMS/IME 자동 입력 등 여러 자리: 붙여넣기와 같은 경로로 처리한다.
      commit(typed, typed.length);
      return;
    }
    const target = Math.min(i, value.length);
    commit(value.slice(0, target) + typed + value.slice(target + 1), target + 1);
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
          <input
            key={i}
            ref={(el) => { refs.current[i] = el; }}
            className="otp__cell"
            inputMode="numeric"
            autoComplete={i === 0 ? 'one-time-code' : 'off'}
            aria-label={`${ariaLabel} ${i + 1}번째 자리`}
            value={d}
            disabled={disabled}
            // autoFocus 는 호출자가 명시적으로 켠 경우에만 적용되는 옵트인 prop 이다.
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus={autoFocus && i === 0}
            maxLength={1}
            onFocus={(e) => e.target.select()}
            onChange={(e) => onInput(i, e.target.value)}
            onPaste={onPaste}
            onKeyDown={(e) => onKeyDown(i, e)}
          />
        ))}
      </div>
    </fieldset>
  );
}
