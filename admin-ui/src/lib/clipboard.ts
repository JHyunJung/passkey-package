// API key 등 민감 문자열 복사용 유틸.
// navigator.clipboard 는 secure context(HTTPS/localhost)에서만 존재하므로,
// HTTP(사내 IP 등) 환경을 위해 execCommand('copy') 폴백을 둔다.
export async function copyToClipboard(text: string): Promise<boolean> {
  // 1) 표준 Clipboard API (secure context)
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // fall through to legacy path
    }
  }
  // 2) 레거시 폴백: 임시 textarea + execCommand('copy')
  const ta = document.createElement('textarea');
  try {
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    ta.remove();
  }
}
