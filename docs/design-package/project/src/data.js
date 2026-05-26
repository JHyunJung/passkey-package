/* global window */
// Mock data for Passkey Admin Console

const TENANTS = [
  {
    id: "tnt_01HBXAC8FE",
    name: "Acme Corp",
    slug: "acme-corp",
    status: "ACTIVE",
    createdAt: "2025-11-14T09:11:02Z",
    rpId: "acme.example.com",
    apiKeys: 3,
    credentials: 14823,
    lastEvent: "2026-05-16T07:18:44Z",
  },
  {
    id: "tnt_01HBKR3QM2",
    name: "Globex Financial",
    slug: "globex-fin",
    status: "ACTIVE",
    createdAt: "2025-09-02T13:44:18Z",
    rpId: "auth.globex-fin.com",
    apiKeys: 5,
    credentials: 92471,
    lastEvent: "2026-05-16T07:21:09Z",
  },
  {
    id: "tnt_01HZ8VYQ1P",
    name: "Initech Health",
    slug: "initech-health",
    status: "ACTIVE",
    createdAt: "2026-01-22T02:08:51Z",
    rpId: "id.initech-health.com",
    apiKeys: 2,
    credentials: 3094,
    lastEvent: "2026-05-16T06:44:12Z",
  },
  {
    id: "tnt_01HXC74WPM",
    name: "Hooli Pay",
    slug: "hooli-pay",
    status: "ACTIVE",
    createdAt: "2025-07-04T22:01:11Z",
    rpId: "passkey.hooli-pay.com",
    apiKeys: 4,
    credentials: 51208,
    lastEvent: "2026-05-16T07:30:22Z",
  },
  {
    id: "tnt_01HV9P22N4",
    name: "Stark Industries",
    slug: "stark-industries",
    status: "ACTIVE",
    createdAt: "2025-04-18T11:23:50Z",
    rpId: "login.stark.io",
    apiKeys: 6,
    credentials: 188204,
    lastEvent: "2026-05-16T07:31:55Z",
  },
  {
    id: "tnt_01HQA77JKL",
    name: "Pied Piper",
    slug: "pied-piper",
    status: "SUSPENDED",
    createdAt: "2024-12-30T16:00:00Z",
    rpId: "id.piedpiper.app",
    apiKeys: 1,
    credentials: 412,
    lastEvent: "2026-04-02T09:00:00Z",
  },
  {
    id: "tnt_01HM4DKSXV",
    name: "Wonka Telecom",
    slug: "wonka-telecom",
    status: "ACTIVE",
    createdAt: "2026-02-10T05:55:32Z",
    rpId: "auth.wonka-tel.com",
    apiKeys: 2,
    credentials: 22481,
    lastEvent: "2026-05-16T07:01:48Z",
  },
  {
    id: "tnt_01HJP8ZQAB",
    name: "Vandelay Bank",
    slug: "vandelay-bank",
    status: "ACTIVE",
    createdAt: "2025-08-19T19:30:21Z",
    rpId: "secure.vandelaybank.com",
    apiKeys: 3,
    credentials: 67230,
    lastEvent: "2026-05-16T07:25:12Z",
  },
  {
    id: "tnt_01HD3QQM91",
    name: "Tyrell Cloud",
    slug: "tyrell-cloud",
    status: "ACTIVE",
    createdAt: "2025-10-11T08:09:00Z",
    rpId: "passkey.tyrellcloud.com",
    apiKeys: 4,
    credentials: 9148,
    lastEvent: "2026-05-16T05:32:00Z",
  },
];

const API_KEYS = {
  "tnt_01HBXAC8FE": [
    { id: "ak_01", prefix: "pk_aB3xY7Q9", name: "production", status: "ACTIVE", createdAt: "2026-05-15T01:22:00Z", lastUsedAt: "2026-05-16T07:18:01Z" },
    { id: "ak_02", prefix: "pk_zZ91qq2P", name: "staging", status: "ACTIVE", createdAt: "2026-05-10T11:08:13Z", lastUsedAt: "2026-05-16T03:42:55Z" },
    { id: "ak_03", prefix: "pk_oldKeyAA", name: "old-rotated", status: "REVOKED", createdAt: "2026-04-01T00:00:00Z", lastUsedAt: "2026-05-09T22:11:09Z" },
  ],
};

const CREDENTIALS = [
  { credId: "Cr3d_8Tg2hPq7vN3jL", externalUserId: "u_889201", nickname: "iPhone 15", status: "ACTIVE", aaguid: "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4", aaguidName: "Apple Passkey", transports: ["internal","hybrid"], signCounter: 41, lastUsedAt: "2026-05-16T07:12:11Z", createdAt: "2026-02-04T11:08:00Z" },
  { credId: "Cr3d_K9wP21nLm8Xs2", externalUserId: "u_889201", nickname: "MacBook Pro", status: "ACTIVE", aaguid: "adce0002-35bc-c60a-648b-0b25f1f05503", aaguidName: "Chrome Profile", transports: ["internal","hybrid"], signCounter: 18, lastUsedAt: "2026-05-15T22:44:50Z", createdAt: "2026-03-12T09:01:11Z" },
  { credId: "Cr3d_Q1aS33XdRtY4z", externalUserId: "u_412903", nickname: "YubiKey 5C", status: "ACTIVE", aaguid: "fa2b99dc-9e39-4257-8f92-4a30d23c4118", aaguidName: "YubiKey 5 Series", transports: ["usb","nfc"], signCounter: 132, lastUsedAt: "2026-05-16T06:01:33Z", createdAt: "2025-12-19T18:20:30Z" },
  { credId: "Cr3d_X9LkJ22Hg44Bn", externalUserId: "u_771203", nickname: "Pixel 8", status: "ACTIVE", aaguid: "08987058-cadc-4b81-b6e1-30de50dcbe96", aaguidName: "Google Password Manager", transports: ["internal","hybrid"], signCounter: 7, lastUsedAt: "2026-05-16T01:15:09Z", createdAt: "2026-04-30T12:00:00Z" },
  { credId: "Cr3d_M2nB9pTzC55Vh", externalUserId: "u_412903", nickname: "Windows Hello", status: "ACTIVE", aaguid: "08a5e7c9-7c5d-4f4f-aa9b-6e6c92fbaa72", aaguidName: "Windows Hello", transports: ["internal"], signCounter: 89, lastUsedAt: "2026-05-15T20:00:21Z", createdAt: "2026-01-09T08:30:00Z" },
  { credId: "Cr3d_R4dT88vWqAa11", externalUserId: "u_998211", nickname: "1Password", status: "REVOKED", aaguid: "bada5566-a7aa-401f-bd96-45619a55120d", aaguidName: "1Password", transports: ["internal","hybrid"], signCounter: 312, lastUsedAt: "2026-04-22T14:11:00Z", createdAt: "2025-08-04T15:45:00Z" },
  { credId: "Cr3d_J7uY12pNqXzZ4", externalUserId: "u_445510", nickname: "iPad Air", status: "ACTIVE", aaguid: "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4", aaguidName: "Apple Passkey", transports: ["internal","hybrid"], signCounter: 23, lastUsedAt: "2026-05-15T19:30:00Z", createdAt: "2026-03-22T08:18:00Z" },
  { credId: "Cr3d_Z8mLp1xRsTu2Hh", externalUserId: "u_771203", nickname: "Galaxy S24", status: "ACTIVE", aaguid: "08987058-cadc-4b81-b6e1-30de50dcbe96", aaguidName: "Google Password Manager", transports: ["internal","hybrid"], signCounter: 4, lastUsedAt: "2026-05-14T11:00:00Z", createdAt: "2026-05-01T10:00:00Z" },
];

const AUDIT_EVENTS = [
  { ts: "2026-05-16T07:31:55Z", type: "CREDENTIAL_AUTHENTICATED", actorType: "RP_SERVICE", actorId: "ak_aB3xY7Q9", subjectType: "CREDENTIAL", subjectId: "Cr3d_8Tg2hPq7vN3jL", payload: { externalUserId: "u_889201", origin: "https://acme.example.com", uvFlag: true, ip: "203.0.113.42" } },
  { ts: "2026-05-16T07:30:22Z", type: "CREDENTIAL_AUTHENTICATED", actorType: "RP_SERVICE", actorId: "ak_aB3xY7Q9", subjectType: "CREDENTIAL", subjectId: "Cr3d_Q1aS33XdRtY4z", payload: { externalUserId: "u_412903", origin: "https://acme.example.com", uvFlag: true, ip: "198.51.100.18" } },
  { ts: "2026-05-16T07:28:01Z", type: "CREDENTIAL_REGISTERED", actorType: "RP_SERVICE", actorId: "ak_zZ91qq2P", subjectType: "CREDENTIAL", subjectId: "Cr3d_W2eRtY55Hh99Pp", payload: { externalUserId: "u_771203", aaguid: "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4", transports: ["internal","hybrid"] } },
  { ts: "2026-05-16T06:51:18Z", type: "SIGNATURE_COUNTER_REGRESSION", actorType: "RP_SERVICE", actorId: "ak_aB3xY7Q9", subjectType: "CREDENTIAL", subjectId: "Cr3d_M2nB9pTzC55Vh", payload: { previous: 91, received: 89, decision: "REJECTED", policy: "STRICT" } },
  { ts: "2026-05-16T05:18:44Z", type: "API_KEY_ISSUED", actorType: "ADMIN", actorId: "adm_jhyun_01", subjectType: "API_KEY", subjectId: "ak_aB3xY7Q9", payload: { name: "production", issuedBy: "jhyun@crosscert.com" } },
  { ts: "2026-05-16T05:00:01Z", type: "ATTESTATION_TRUST_FAILED", actorType: "RP_SERVICE", actorId: "ak_zZ91qq2P", subjectType: "CREDENTIAL", subjectId: "—", payload: { aaguid: "00000000-0000-0000-0000-000000000000", reason: "AAGUID not in allowlist", policy: "ALLOWLIST" } },
  { ts: "2026-05-15T22:14:00Z", type: "WEBAUTHN_CONFIG_UPDATED", actorType: "ADMIN", actorId: "adm_jhyun_01", subjectType: "TENANT", subjectId: "tnt_01HBXAC8FE", payload: { changes: { origins: { added: ["https://staging.acme.example.com"] } } } },
  { ts: "2026-05-15T20:18:09Z", type: "CREDENTIAL_REVOKED", actorType: "ADMIN", actorId: "adm_kim_iam", subjectType: "CREDENTIAL", subjectId: "Cr3d_R4dT88vWqAa11", payload: { reason: "user reported lost device" } },
  { ts: "2026-05-15T15:08:22Z", type: "API_KEY_REVOKED", actorType: "ADMIN", actorId: "adm_kim_iam", subjectType: "API_KEY", subjectId: "ak_oldKeyAA", payload: { name: "old-rotated", reason: "scheduled rotation" } },
  { ts: "2026-05-15T11:11:11Z", type: "ATTESTATION_POLICY_UPDATED", actorType: "ADMIN", actorId: "adm_jhyun_01", subjectType: "TENANT", subjectId: "tnt_01HBXAC8FE", payload: { mode: "ALLOWLIST", added: 2 } },
];

const FUNNEL = {
  windowDays: 7,
  registration: { attempts: 4218, success: 4039, ratio: 0.957 },
  authentication: { attempts: 84102, success: 83217, ratio: 0.9895 },
  conversion: 0.823,
  // 7 day mini series (auth attempts per day) and success per day
  series: [
    { day: "월", attempts: 11200, success: 11058 },
    { day: "화", attempts: 12104, success: 11991 },
    { day: "수", attempts: 11894, success: 11770 },
    { day: "목", attempts: 12340, success: 12180 },
    { day: "금", attempts: 13702, success: 13510 },
    { day: "토", attempts: 11588, success: 11488 },
    { day: "일", attempts: 11274, success: 11220 },
  ],
  byEventType: [
    { type: "REG_ATTEMPT", n: 4218 },
    { type: "REG_SUCCESS", n: 4039 },
    { type: "AUTH_ATTEMPT", n: 84102 },
    { type: "AUTH_SUCCESS", n: 83217 },
    { type: "REG_FAIL", n: 179 },
    { type: "AUTH_FAIL", n: 885 },
  ],
};

const WEBAUTHN_CONFIG = {
  rpId: "acme.example.com",
  rpName: "Acme Corp",
  origins: ["https://acme.example.com", "https://app.acme.example.com"],
  timeoutMs: 60000,
  userVerification: "REQUIRED",
  attestationConveyance: "DIRECT",
};

const ATTESTATION_POLICY = {
  mode: "ALLOWLIST",
  allowed: [
    "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4",
    "08987058-cadc-4b81-b6e1-30de50dcbe96",
    "fa2b99dc-9e39-4257-8f92-4a30d23c4118",
    "08a5e7c9-7c5d-4f4f-aa9b-6e6c92fbaa72",
  ],
  denied: [],
  mdsStrict: false,
};

const AAGUID_NAMES = {
  "ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4": "Apple Passkey",
  "08987058-cadc-4b81-b6e1-30de50dcbe96": "Google Password Manager",
  "fa2b99dc-9e39-4257-8f92-4a30d23c4118": "YubiKey 5 Series",
  "08a5e7c9-7c5d-4f4f-aa9b-6e6c92fbaa72": "Windows Hello",
  "adce0002-35bc-c60a-648b-0b25f1f05503": "Chrome Profile",
  "bada5566-a7aa-401f-bd96-45619a55120d": "1Password",
};

const ADMIN_USERS = {
  platform: { adminId: "adm_jhyun_01", role: "PLATFORM_OPERATOR", tenantId: null, displayName: "정 운영자", email: "jhyun@crosscert.com" },
  rp: { adminId: "adm_kim_iam", role: "RP_ADMIN", tenantId: "tnt_01HBXAC8FE", displayName: "김 IAM담당", email: "kim.iam@acme.example.com" },
};

window.MOCK = { TENANTS, API_KEYS, CREDENTIALS, AUDIT_EVENTS, FUNNEL, WEBAUTHN_CONFIG, ATTESTATION_POLICY, AAGUID_NAMES, ADMIN_USERS };
