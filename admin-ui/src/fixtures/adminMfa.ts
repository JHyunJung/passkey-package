// AdminUsersTab (pages-6.jsx) ADMIN_LIST 의 mfa 필드.
// userId → MFA enabled 매핑.

// MFA status from pages-6 ADMIN_LIST:
//   adm_jhyun_01  → true
//   adm_park_op   → true
//   adm_kim_iam   → true
//   adm_lee_globex→ false
//   adm_choi_hooli→ true

export const adminMfaFixture: Record<string, boolean> = {
  adm_jhyun_01:   true,
  adm_park_op:    true,
  adm_kim_iam:    true,
  adm_lee_globex: false,
  adm_choi_hooli: true,
};

export function getMfa(userId: string): boolean {
  return adminMfaFixture[userId] ?? false;
}
