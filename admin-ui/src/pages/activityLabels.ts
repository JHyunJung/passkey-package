// 액션 코드 → 한글 라벨, 그리고 피드 행을 사람이 읽는 문장으로 조립한다.
// 매핑에 없는 코드는 원문 그대로 노출(fallback).

const ACTION_LABELS: Record<string, string> = {
  TENANT_CREATE: '테넌트 생성',
  TENANT_UPDATE: '테넌트 수정',
  CREDENTIAL_REVOKE: 'Credential 폐기',
  CREDENTIAL_REVOKED: 'Credential 폐기',
  CREDENTIAL_REGISTERED: 'Credential 등록',
  CREDENTIAL_AUTHENTICATED: '인증 성공',
  API_KEY_ISSUE: 'API 키 발급',
  API_KEY_ISSUED: 'API 키 발급',
  API_KEY_REVOKE: 'API 키 회수',
  API_KEY_REVOKED: 'API 키 회수',
  SIGNING_KEY_ROTATE: '서명키 회전',
  WEBAUTHN_CONFIG_UPDATED: '설정 변경',
  ATTESTATION_POLICY_UPDATED: 'AAGUID 정책 변경',
  ADMIN_LOGIN: '관리자 로그인',
  ADMIN_LOGIN_FAILED: '로그인 실패',
  SIGNATURE_COUNTER_REGRESSION: '서명 카운터 이상',
  ATTESTATION_TRUST_FAILED: 'Attestation 신뢰 실패',
  MDS_BLOB_SYNC: 'MDS 동기화',
  RETENTION_PURGE: '보존기간 정리',
};

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export interface EventSentenceInput {
  action: string;
  actorEmail: string;
  tenantSlug: string | null;
  targetType: string | null;
  targetId: string | null;
}

/** "{행위자} 님이 {테넌트} 테넌트에 {대상} {액션}" 형태의 한 줄 문장. */
export function eventSentence(e: EventSentenceInput): string {
  const actor = e.actorEmail && e.actorEmail.trim().length > 0 ? e.actorEmail : 'system';
  const label = actionLabel(e.action);
  const tenant = e.tenantSlug ? `${e.tenantSlug} 테넌트` : '플랫폼';
  const target =
    e.targetType && e.targetId
      ? `${e.targetType} ${e.targetId}`
      : e.targetId ?? '';
  return target
    ? `${actor} 님이 ${tenant}에 ${target} · ${label}`
    : `${actor} · ${tenant} · ${label}`;
}
