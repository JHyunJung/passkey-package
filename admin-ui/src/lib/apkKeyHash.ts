// Android 네이티브 WebAuthn 클라이언트의 clientData.origin 은
//   android:apk-key-hash:<base64url(SHA-256(서명인증서))>  (padding 없는 43자)
// 형태다. 테넌트는 keytool 출력의 SHA-256 지문(hex)을 그대로 붙여넣고,
// 여기서 base64url 로 변환한다.

export const APK_KEY_HASH_PREFIX = 'android:apk-key-hash:';

export function isApkKeyHashOrigin(s: string): boolean {
  return s.startsWith(APK_KEY_HASH_PREFIX);
}

// 바이트 배열 → base64url(no padding)
function toBase64Url(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function apkKeyHashFromFingerprint(
  input: string,
): { ok: true; value: string } | { ok: false; error: string } {
  // 콜론·공백 제거
  const hex = input.replace(/[\s:]/g, '');
  if (hex.length === 0) {
    return { ok: false, error: 'SHA-256 지문을 입력하세요.' };
  }
  if (hex.length !== 64) {
    return {
      ok: false,
      error: `SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: ${hex.length}`,
    };
  }
  if (!/^[0-9a-fA-F]{64}$/.test(hex)) {
    return { ok: false, error: '지문에 16진수가 아닌 문자가 있습니다.' };
  }
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return { ok: true, value: APK_KEY_HASH_PREFIX + toBase64Url(bytes) };
}
