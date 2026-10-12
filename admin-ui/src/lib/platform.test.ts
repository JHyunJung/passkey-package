import { describe, it, expect } from 'vitest';
import { isMacPlatform, paletteShortcutLabel } from './platform';

describe('platform', () => {
  it('Mac·iOS 는 Mac 으로 본다', () => {
    expect(isMacPlatform({ platform: 'MacIntel' })).toBe(true);
    expect(isMacPlatform({ platform: 'iPhone' })).toBe(true);
  });
  it('Windows·Linux 는 Mac 이 아니다', () => {
    expect(isMacPlatform({ platform: 'Win32' })).toBe(false);
    expect(isMacPlatform({ platform: 'Linux x86_64' })).toBe(false);
  });
  it('platform 이 비면 userAgent 로 판단', () => {
    expect(isMacPlatform({ platform: '', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)' })).toBe(true);
    expect(isMacPlatform({ platform: '', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' })).toBe(false);
  });
  it('navigator 가 없으면 Mac 아님', () => {
    expect(isMacPlatform(undefined)).toBe(false);
  });
  it('팔레트 단축키 표기', () => {
    expect(paletteShortcutLabel(true)).toBe('⌘K');
    expect(paletteShortcutLabel(false)).toBe('Ctrl K');
  });
});
