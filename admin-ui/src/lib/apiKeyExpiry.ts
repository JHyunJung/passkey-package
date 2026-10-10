export const EXPIRY_WARN_DAYS = 30;
const DAY_MS = 86_400_000;

/** 회수되지 않았고, 아직 유효하며, now 로부터 days 일 이내에 만료되는 키. 무기한(expiresAt 없음)은 false. */
export function isExpiringSoon(
  k: { revokedAt?: string | null; expiresAt?: string | null },
  now: Date,
  days: number = EXPIRY_WARN_DAYS,
): boolean {
  if (k.revokedAt || !k.expiresAt) return false;
  const exp = new Date(k.expiresAt).getTime();
  if (Number.isNaN(exp)) return false;
  const t = now.getTime();
  return exp > t && exp - t <= days * DAY_MS;
}
