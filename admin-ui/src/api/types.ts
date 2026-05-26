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
