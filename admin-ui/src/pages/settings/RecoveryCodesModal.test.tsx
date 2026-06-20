import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { RecoveryCodesModal } from './RecoveryCodesModal';
import { copyToClipboard } from '@/lib/clipboard';

// Copy must route through the shared copyToClipboard util (which has the
// execCommand fallback for insecure HTTP contexts). Calling
// navigator.clipboard directly silently no-ops on non-secure origins, which
// was the original "복사가 안 된다" bug.
vi.mock('@/lib/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}));

const CODES = ['3f9a-2b71', '8c4d-9e02', 'a17b-44ff', '6620-d3a9', 'e591-7c08',
               '1bd4-aa3e', '9038-5f6c', '72e1-0b8d', 'c4a6-118f', '5d20-93b7'];

describe('RecoveryCodesModal', () => {
  beforeEach(() => {
    vi.mocked(copyToClipboard).mockClear();
    vi.mocked(copyToClipboard).mockResolvedValue(true);
  });

  it('copy button routes through copyToClipboard with all codes joined by newline', async () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /복사/ }));
    await waitFor(() => expect(copyToClipboard).toHaveBeenCalledWith(CODES.join('\n')));
  });

  it('shows a manual-copy hint when copyToClipboard fails', async () => {
    vi.mocked(copyToClipboard).mockResolvedValue(false);
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /복사/ }));
    await waitFor(() => expect(screen.getByText(/복사에 실패했습니다/)).toBeInTheDocument());
  });

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
