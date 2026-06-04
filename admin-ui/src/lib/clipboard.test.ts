import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { copyToClipboard } from './clipboard';

// jsdom omits document.execCommand, so vi.spyOn(document, 'execCommand')
// throws "execCommand does not exist". Define a stub before each test so the
// spy has a property to replace; afterEach removes it again.
//
// jsdom defaults window.isSecureContext to undefined (insecure). The modern
// Clipboard API path is now gated behind secure context, so tests that
// exercise it call secureContext(true); fallback tests rely on the insecure
// default.
function setSecureContext(value: boolean): void {
  Object.defineProperty(window, 'isSecureContext', {
    value,
    configurable: true,
    writable: true,
  });
}

beforeEach(() => {
  Object.defineProperty(document, 'execCommand', {
    value: () => false,
    configurable: true,
    writable: true,
  });
  setSecureContext(false);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (document as { execCommand?: unknown }).execCommand;
  delete (window as { isSecureContext?: unknown }).isSecureContext;
});

describe('copyToClipboard', () => {
  it('uses navigator.clipboard.writeText when available and returns true', async () => {
    setSecureContext(true);
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(writeText).toHaveBeenCalledWith('secret');
  });

  it('skips the modern API in an insecure context and falls back to execCommand', async () => {
    setSecureContext(false);
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const execCommand = vi.fn().mockReturnValue(true);
    vi.spyOn(document, 'execCommand' as never).mockImplementation(execCommand as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(true);
    expect(writeText).not.toHaveBeenCalled();
    expect(execCommand).toHaveBeenCalledWith('copy');
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
    setSecureContext(true);
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

  it('removes the temporary textarea after a successful fallback', async () => {
    vi.stubGlobal('navigator', {});
    vi.spyOn(document, 'execCommand' as never).mockImplementation((() => true) as never);
    await copyToClipboard('secret');
    expect(document.querySelector('textarea')).toBeNull();
  });

  it('removes the temporary textarea even when execCommand throws', async () => {
    vi.stubGlobal('navigator', {});
    vi.spyOn(document, 'execCommand' as never).mockImplementation((() => { throw new Error('boom'); }) as never);
    const ok = await copyToClipboard('secret');
    expect(ok).toBe(false);
    expect(document.querySelector('textarea')).toBeNull();
  });
});
