import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { copyToClipboard } from './clipboard';

// jsdom omits document.execCommand, so vi.spyOn(document, 'execCommand')
// throws "execCommand does not exist". Define a stub before each test so the
// spy has a property to replace; afterEach removes it again.
beforeEach(() => {
  Object.defineProperty(document, 'execCommand', {
    value: () => false,
    configurable: true,
    writable: true,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (document as { execCommand?: unknown }).execCommand;
});

describe('copyToClipboard', () => {
  it('uses navigator.clipboard.writeText when available and returns true', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(writeText).toHaveBeenCalledWith('secret');
  });

  it('falls back to execCommand when navigator.clipboard is absent', async () => {
    vi.stubGlobal('navigator', {}); // no clipboard
    const execCommand = vi.fn().mockReturnValue(true);
    vi.spyOn(document, 'execCommand' as never).mockImplementation(execCommand as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('falls back to execCommand when writeText rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const execCommand = vi.fn().mockReturnValue(true);
    vi.spyOn(document, 'execCommand' as never).mockImplementation(execCommand as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('returns false when both clipboard and execCommand fail', async () => {
    vi.stubGlobal('navigator', {});
    vi.spyOn(document, 'execCommand' as never).mockImplementation((() => false) as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(false);
  });
});
