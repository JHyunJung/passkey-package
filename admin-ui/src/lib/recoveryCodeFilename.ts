/**
 * 복구 코드 다운로드 파일명을 생성한다.
 *   passkey-admin-recovery-codes-<account>-<YYYYMMDD>.txt
 * <account> = email 의 @ 앞 local part 를 파일명-안전하게 sanitize.
 * email 이 없거나 sanitize 결과가 비면 account 세그먼트를 생략한다.
 */
export function recoveryCodesFilename(email: string | null | undefined, now: Date): string {
  const stamp = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const account = sanitizeAccount(email);
  const base = 'passkey-admin-recovery-codes';
  return account ? `${base}-${account}-${stamp}.txt` : `${base}-${stamp}.txt`;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function sanitizeAccount(email: string | null | undefined): string {
  if (!email) return '';
  const local = email.split('@')[0] ?? '';
  return local
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-') // 안전 문자만 유지, 나머지는 대시
    .replace(/-+/g, '-')            // 연속 대시 축약
    .replace(/^-+|-+$/g, '');       // 양끝 대시 제거
}
