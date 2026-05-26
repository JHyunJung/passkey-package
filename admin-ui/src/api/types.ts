export interface Me {
  email: string;
  role: 'ADMIN' | 'VIEWER';
}

export interface TenantView {
  id: string;
  displayName: string;
  status: string;
  rpId: string;
  rpName: string;
  allowedOriginsJson: string;
  attestationPolicyJson: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantCreateRequest {
  id: string;
  displayName: string;
  rpId: string;
  rpName: string;
  allowedOriginsJson: string;
  attestationPolicyJson: string;
}

export interface ApiKeyView {
  id: number;
  prefix: string;
  name: string;
  tenantId: string;
  createdAt: string;
  lastUsedAt?: string;
  expiresAt?: string;
  revokedAt?: string;
}

export interface ApiKeyCreateRequest {
  tenantId: string;
  name: string;
  scopesJson: string;
  expiresAt?: string;
}

export interface ApiKeyCreateResponse {
  id: number;
  prefix: string;
  plainText: string;   // ONE-TIME
  name: string;
  tenantId: string;
  createdAt: string;
  expiresAt?: string;
}

export interface AuditLogView {
  id: number;
  actorId: number;
  actorEmail: string;
  action: string;
  targetType?: string;
  targetId?: string;
  payload: string;
  createdAt: string;
}

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
