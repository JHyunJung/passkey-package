// admin-ui/src/pages/settings/RecoveryCodesModal.test.tsx
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
const TITLE = '복구 코드 — 지금만 표시됩니다';

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

  it('renders all codes, numbered 01–10', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    for (const c of CODES) expect(screen.getByText(c)).toBeInTheDocument();
    expect(screen.getByText('01')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: '복구 코드 목록' }).children).toHaveLength(10);
  });

  it('close button is disabled until saved-checkbox is checked', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    const closeBtn = screen.getByRole('button', { name: /닫기|체크/ });
    expect(closeBtn).toBeDisabled();
    expect(closeBtn).toHaveTextContent('닫기 · 체크 필요');
    fireEvent.click(screen.getByRole('checkbox', { name: '안전한 곳에 저장했습니다.' }));
    expect(closeBtn).not.toBeDisabled();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('alertdialog 이고 Escape·스크림 클릭으로 닫히지 않는다 (체크 후에도)', () => {
    const onClose = vi.fn();
    render(<RecoveryCodesModal codes={CODES} onClose={onClose} />);
    expect(screen.getByRole('alertdialog', { name: TITLE })).toHaveAttribute('aria-modal', 'true');
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('복구 코드 일러스트는 장식(alt="")', () => {
    render(<RecoveryCodesModal codes={CODES} onClose={() => {}} />);
    const img = screen.getByRole('alertdialog').querySelector('img');
    expect(img).toHaveAttribute('alt', '');
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
