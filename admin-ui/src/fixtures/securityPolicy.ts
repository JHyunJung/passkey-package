// SecurityPolicyTab (pages-6.jsx) 이 기대하는 형태.
// pages-6 useState 초기값 + CORS allowlist 그대로.

export type SecurityPolicyData = {
  sessionIdleTimeoutMinutes: number;
  passwordMinLength: number;
  mfaRequired: boolean;
  corsAllowlist: string[];
};

// pages-6 SecurityPolicyTab initial state:
//   sessionMin = 30
//   pwMin      = 12
//   reqMfa     = true
//   CORS chip  = "https://admin.passkey.example.com"
export const securityPolicyFixture: SecurityPolicyData = {
  sessionIdleTimeoutMinutes: 30,
  passwordMinLength:         12,
  mfaRequired:               true,
  corsAllowlist: [
    'https://admin.passkey.example.com',
  ],
};
