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
  id: number;
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
