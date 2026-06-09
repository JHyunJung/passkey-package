import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RecoveryCodesModal } from './RecoveryCodesModal';

const CODES = ['3f9a-2b71', '8c4d-9e02', 'a17b-44ff', '6620-d3a9', 'e591-7c08',
               '1bd4-aa3e', '9038-5f6c', '72e1-0b8d', 'c4a6-118f', '5d20-93b7'];

describe('RecoveryCodesModal', () => {
  it('renders all codes', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    for (const c of CODES) expect(screen.getByText(c)).toBeInTheDocument();
  });

  it('close button is disabled until saved-checkbox is checked', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    const closeBtn = screen.getByRole('button', { name: /닫기|체크/ });
    expect(closeBtn).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(closeBtn).not.toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('download filename includes sanitized account from accountEmail', () => {
    const clickedNames: string[] = [];
    const origCreate = document.createElement.bind(document);
    const spy = vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = origCreate(tag) as HTMLElement;
      if (tag === 'a') {
        // capture the download name at click time
        (el as HTMLAnchorElement).click = () => { clickedNames.push((el as HTMLAnchorElement).download); };
      }
      return el;
    });
    // jsdom lacks createObjectURL/revokeObjectURL
    (URL as unknown as { createObjectURL: () => string }).createObjectURL = () => 'blob:x';
    (URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = () => {};

    render(<RecoveryCodesModal codes={CODES} accountEmail="Alice@corp.com" onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /다운로드/ }));

    expect(clickedNames).toHaveLength(1);
    expect(clickedNames[0]).toMatch(/^passkey-admin-recovery-codes-alice-\d{8}\.txt$/);
    spy.mockRestore();
  });
});
