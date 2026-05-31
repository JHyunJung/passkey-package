import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MfaChallenge from './MfaChallenge';
import { ToastHost } from '@/shell/ToastHost';

function renderChallenge() {
  return render(
    <ToastHost>
      <MfaChallenge onVerified={vi.fn()} onLogout={vi.fn()} />
    </ToastHost>,
  );
}

describe('MfaChallenge', () => {
  it('TOTP mode strips non-digits and caps at 6', () => {
    renderChallenge();
    const input = screen.getByPlaceholderText('000000') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'a1b2c3d4e5f6g7' } }); // digits: 1234567 -> cap 6
    expect(input.value).toBe('123456');
  });

  it('recovery mode accepts free-text and clears on toggle', () => {
    renderChallenge();
    // switch to recovery
    fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));
    const rec = screen.getByPlaceholderText('3f9a-2b71') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: '3f9a-2b71' } });
    expect(rec.value).toBe('3f9a-2b71'); // hyphens/letters preserved (no digit sanitize)
  });

  it('toggling back to TOTP clears the input', () => {
    renderChallenge();
    fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));
    const rec = screen.getByPlaceholderText('3f9a-2b71') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: /authenticator 코드로 돌아가기/ }));
    const totp = screen.getByPlaceholderText('000000') as HTMLInputElement;
    expect(totp.value).toBe(''); // switchMode clears code
  });
});
