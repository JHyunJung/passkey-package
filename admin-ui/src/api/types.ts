export interface Me {
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  tenantId: string | null;
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
}

export interface ApiKeyCreateRequest {
  tenantId: string;
  name: string;
  scopes: string[];        // was scopesJson: string
  expiresAt?: string;
}

export interface ApiKeyCreateResponse {
  id: string;          // UUID
  prefix: string;
  plainText: string;   // ONE-TIME
  scopes: string[];
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
}

export interface TenantUpdateRequest {
  displayName: string;
  rpName: string;
  allowedOrigins: string[];
  acceptedFormats: string[];
  requireUserVerification: boolean;
  mdsRequired: boolean;
}

export interface CredentialView {
  credentialId: string;
  userHandle: string;
  aaguidHex: string | null;
  authenticatorName: string | null;
  attestationFormat: string;
  transports: string;
  signCount: number;
  lastUsedAt: string | null;
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
