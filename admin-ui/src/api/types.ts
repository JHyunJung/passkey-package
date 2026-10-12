export interface Me {
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  /** /me 응답의 tenantIds (복수). RP_ADMIN은 ≥1개, PLATFORM_OPERATOR는 빈 배열. */
  tenantIds: string[];
  mfaEnabled: boolean;
  mfaRequired: boolean;
  sessionIdleTimeoutMinutes: number;
  /** 스펙 §6 ④ — MFA 등록 시각(ISO). 서버가 내려주기 전까지 undefined → 내 계정 "등록일" 줄을 렌더하지 않는다. */
  mfaEnrolledAt?: string;
  /** 스펙 §6 ④ — 남은 복구 코드 수(최대 10). undefined → "남은 복구 코드" 줄을 렌더하지 않는다. */
  recoveryCodesRemaining?: number;
}

export interface TenantView {
  id: string;          // UUID string (e.g. "550e8400-e29b-41d4-a716-446655440000")
  slug: string;        // operator-readable identifier (e.g. "acme", "globex")
  displayName: string;
  status: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];         // was allowedOriginsJson: string
  acceptedFormats: string[];        // was inside attestationPolicyJson
  requireUserVerification: boolean; // was inside attestationPolicyJson
  mdsRequired: boolean;             // was inside attestationPolicyJson
  createdAt: string;
  updatedAt: string;
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
  /** 스펙 §6 ⑤ 지표 보강(서버 후속). 최근 7일 credential 증감. 없으면 KPI 보조 줄을 그리지 않는다. */
  credentialsDelta7d?: number;
}

export interface TenantCreateRequest {
  slug: string;
  displayName: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];
  acceptedFormats: string[];
  requireUserVerification: boolean;
  mdsRequired: boolean;
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
}

export interface ApiKeyView {
  id: string;          // UUID
  keyPrefix: string;
  name: string;
  tenantId: string;
  scopes: string[];        // was scopesJson: string
  createdAt: string;
  lastUsedAt?: string;
  expiresAt?: string;
  revokedAt?: string;
  keySuffix?: string;      // 스펙 §6 ⑤ — 서버 후속 작업에서 추가. 없으면 렌더하지 않는다.
}

export interface ApiKeyCreateRequest {
  tenantId: string;
  name: string;
  scopes: string[];        // was scopesJson: string
  expiresAt?: string;
  expiresInMonths?: number | null;   // null/생략 = 무기한
}

export interface ApiKeyCreateResponse {
  id: string;          // UUID
  prefix: string;
  plainText: string;   // ONE-TIME
  scopes: string[];
  expiresAt?: string | null;
}

export interface ApiKeyRotateResponse {
  id: string;
  plaintextKey: string;   // ONE-TIME
  prefix: string;
  scopes: string[];
  oldKeyExpiresAt: string; // ISO instant
}

export interface AuditLogView {
  id: number;
  actorId: number;
  actorEmail: string;
  tenantId: string | null;
  action: string;
  targetType?: string;
  targetId?: string;
  payload: string;
  createdAt: string;
  result?: 'SUCCESS' | 'FAILURE';   // 스펙 §6 ③ AuditRow.result — 없으면 실패 판정은 액션 이름 기준
}

export interface ActivityKpi {
  events24h: number;
  ops24h: number;
  security24h: number;
  p95Ms: number | null;
}

export interface ActivityTopTenant {
  tenantId: string;
  slug: string;
  count: number;
}

export interface ActivityEvent {
  id: string;
  action: string;
  actorEmail: string;
  targetType: string | null;
  targetId: string | null;
  tenantId: string | null;
  tenantSlug: string | null;
  createdAt: string;
  category: 'ops' | 'security' | 'system';
  /** 스펙 §6 ③ 로그 보강(서버 후속). 없으면 상세 패널 IP 줄을 그리지 않는다. */
  actorIp?: string;
  /** 스펙 §6 ③. 없으면 payload 의 before/after(TENANT_UPDATE)로 diff 를 만든다. */
  diff?: { field: string; from: unknown; to: unknown }[];
}

export interface ActivityDetailView {
  id: string;
  action: string;
  actorId: string | null;
  actorEmail: string;
  targetType: string | null;
  targetId: string | null;
  tenantId: string | null;
  tenantSlug: string | null;
  createdAt: string;
  category: 'ops' | 'security' | 'system';
  payload: string; // canonical JSON string
  /** 스펙 §6 ③ 로그 보강(서버 후속). 없으면 상세 패널 IP 줄을 그리지 않는다. */
  actorIp?: string;
  /** 스펙 §6 ③. 없으면 payload 의 before/after(TENANT_UPDATE)로 diff 를 만든다. */
  diff?: { field: string; from: unknown; to: unknown }[];
}

export interface ActivityView {
  kpi: ActivityKpi;
  top5: ActivityTopTenant[];
  feed: ActivityEvent[];
}

export type ActivityCategory = 'all' | 'ops' | 'security';

export interface MdsStatusView {
  version: number;
  nextUpdate?: string;
  fetchedAt?: string;
}

export interface SyncResult {
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  version?: number;
  error?: string;
}

export interface SigningKeyView {
  id: string;
  kid: string;
  alg: string;
  status: 'ACTIVE' | 'ROTATED' | 'REVOKED';
  createdAt: string;
  rotatedAt?: string;
  revokedAt?: string;
}

export interface KeyList {
  keys: SigningKeyView[];
}

export interface RotateResponse {
  oldKid: string;
  newKid: string;
}

// Phase 4 — server returns every endpoint (except /.well-known/jwks.json)
// wrapped in this envelope. client.ts strips it so page code keeps using
// the inner typed payload directly. ApiError carries the error envelope
// when success=false so try/catch in pages can branch on code + traceId.
export interface FieldError {
  field: string;
  rejectedValue: unknown;
  reason: string;
}

export interface ApiEnvelope<T> {
  success: boolean;
  code: string;
  message: string;
  data?: T;
  error?: {
    errorCode: string;
    fieldErrors?: FieldError[];
  };
  traceId?: string;
  timestamp?: string;
}

export class ApiError extends Error {
  constructor(
    public readonly httpStatus: number,
    public readonly code: string,
    public readonly serverMessage: string,
    public readonly fieldErrors?: FieldError[],
    public readonly traceId?: string
  ) {
    super(`[${code}] ${serverMessage}`);
    this.name = 'ApiError';
  }

  /** HTTP status alias — callers/tests reference `.status`. */
  get status(): number {
    return this.httpStatus;
  }
}

/** 응답이 시간 제한 안에 오지 않아 요청을 중단함 — 일시 오류로 취급한다(isTransientError). */
export class RequestTimeoutError extends Error {
  constructor(public readonly timeoutMs: number) {
    super(`Request timed out after ${timeoutMs}ms`);
    this.name = 'RequestTimeoutError';
  }
}

export interface TenantUpdateRequest {
  displayName: string;
  rpId: string;
  rpName: string;
  allowedOrigins: string[];
  acceptedFormats: string[];
  requireUserVerification: boolean;
  mdsRequired: boolean;
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE';
  webauthnTimeoutMs: number;
}

export interface CredentialView {
  credentialId: string;
  userHandle: string;
  label: string | null;
  aaguidHex: string | null;
  authenticatorName: string | null;
  attestationFormat: string;
  transports: string;
  signCount: number;
  lastUsedAt: string | null;
  createdAt: string;
}

export interface AuthEventView {
  result: string;          // "SUCCESS" | "FAILED" (서버 상수)
  failureReason: string | null;
  signCount: number;
  createdAt: string;
}

export interface PageView<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  hasNext: boolean;
}

export type AuditChainOverview = {
  verifiedAt: string;
  windowHours: number;
  bucketSizeMinutes: number;
  totals: {
    tenantsIntact: number;
    tenantsTotal: number;
    tenantsTampered: number;
    verifiedRows: number;
    verificationMs: number;
  };
  tenants: {
    tenantId: string | null;
    tenantName: string;
    intact: boolean;
    verifiedRows: number;
    buckets: number[];
    tamperedEntryId: string | null;
  }[];
};

export type ChainVerifyResponse = {
  tenantId: string | null;
  intact: boolean;
  tamperedEntryId: string | null;
  verifiedAt: string;
};

export type BackfillResponse = {
  tenantsProcessed: number;
  rowsUpdated: number;
  rowsSkipped: number;
};

export type AaguidPolicyMode = 'ANY' | 'ALLOWLIST' | 'DENYLIST';

export type AaguidPolicyEntry = {
  aaguid: string;
  note: string | null;
  mdsName: string | null;
};

export type AaguidPolicyView = {
  tenantId: string;
  mode: AaguidPolicyMode;
  mdsStrict: boolean;
  entries: AaguidPolicyEntry[];
  updatedAt: string;
  updatedBy: string | null;
};

export type AaguidPolicyUpdateRequest = {
  mode: AaguidPolicyMode;
  mdsStrict: boolean;
  entries: { aaguid: string; note: string | null }[];
};

export type WebauthnDiffRequest = {
  rpId?: string | null;
  rpName?: string | null;
  allowedOrigins?: string[] | null;
  acceptedFormats?: string[] | null;
  requireUserVerification?: boolean | null;
  mdsRequired?: boolean | null;
};

export type WebauthnConfigDiff = {
  current: { rpId: string; rpName: string; origins: string[]; formats: string[]; requireUserVerification: boolean; mdsRequired: boolean };
  proposed: { rpId: string; rpName: string; origins: string[]; formats: string[]; requireUserVerification: boolean; mdsRequired: boolean };
  changes: { field: string; from: unknown; to: unknown; added: string[] | null; removed: string[] | null }[];
  warnings: string[];
};

export type AdminUserStatus = 'ACTIVE' | 'PENDING' | 'SUSPENDED';

export type AdminUserView = {
  id: string;
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  status: AdminUserStatus;
  tenantIds: string[];
  createdAt: string;
  lastLoginAt: string | null;
  suspendedAt: string | null;
  createdBy: string | null;
};
