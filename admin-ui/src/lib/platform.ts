type NavLike = { platform?: string; userAgent?: string };

/** macOS·iOS 인지. navigator.platform 이 비어 있으면 userAgent 로 판단한다. */
export function isMacPlatform(nav: NavLike | undefined = typeof navigator === 'undefined' ? undefined : navigator): boolean {
  if (!nav) return false;
  return /Mac|iPhone|iPad|iPod/.test(nav.platform || nav.userAgent || '');
}

/** 명령 팔레트 단축키 표기(H4 플랫폼 관례). Mac 은 ⌘K, 그 밖은 Ctrl K. */
export function paletteShortcutLabel(mac: boolean = isMacPlatform()): '⌘K' | 'Ctrl K' {
  return mac ? '⌘K' : 'Ctrl K';
}
