// 디자인 패키지의 data.js / jsx 가 기대하는 type
// 서버 type (api/types.ts) 과 별도 — 어댑터(api/*.ts) 가 변환

export type Tenant = {
  id: string;
  name: string;
  slug: string;
  rpId: string;
  status: 'ACTIVE' | 'SUSPENDED';
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
  createdAt: string;
};

export type ApiKey = {
  id: string;
  prefix: string;
  name: string;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
  lastUsedAt: string | null;
};

export type Credential = {
  credentialId: string;
  externalUserId: string;
  nickname: string | null;
  status: 'ACTIVE' | 'REVOKED';
  aaguid: string | null;
  transports: string[];
  signatureCounter: number;
  lastUsedAt: string | null;
  createdAt: string;
};

export type AuditEvent = {
  id: string;
  ts: string;
  eventType: string;
  actorType: string;
  actorId: string | null;
  subjectType: string | null;
  subjectId: string | null;
  payload: Record<string, unknown> | null;
};

export type WebauthnConfig = {
  rpId: string;
  rpName: string;
  origins: string[];
  formats: string[];
  userVerification: 'REQUIRED' | 'PREFERRED' | 'DISCOURAGED';
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT';
  timeoutMs: number;
};

export type AaguidPolicy = {
  mode: 'ANY' | 'ALLOWLIST' | 'DENYLIST';
  mdsStrict: boolean;
  entries: { aaguid: string; note: string | null; mdsName: string | null }[];
};

export type ChainVerifyResult = {
  intact: boolean;
  verifiedRows: number;
  tamperedEntryIds: string[];
  verifiedAt: string;
};
