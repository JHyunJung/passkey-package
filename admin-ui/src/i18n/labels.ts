// 화면 표시 라벨 — enum 값(영어)은 코드 로직·API에 그대로 쓰고, 화면에 그릴 때만 변환한다.

/** 상태 배지 공용 라벨. enum 값은 영어 유지, 표시만 한국어. */
export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
  REVOKED: '회수',
  EXPIRED: '만료',
  PENDING: '대기',
  INTACT: '정상',
  TAMPERED: '위변조',
  OPEN: '처리중',
  RESOLVED: '해결',
  SUCCESS: '성공',
  FAILED: '실패',
  ROTATED: '교체됨',
  SYNCED: '동기화됨',
  SKIPPED: '건너뜀',
};

/** 상태 enum 값을 한국어 표시 라벨로. 미매핑 값은 원본 그대로 반환(안전한 fallback). */
export const statusLabel = (v: string): string => STATUS_LABELS[v] ?? v;

/** AAGUID 정책 모드 enum → 한국어. enum 값(ANY/ALLOWLIST/DENYLIST)은 영어 유지. */
export const AAGUID_MODE_LABELS: Record<string, string> = {
  ANY: '전체 허용',
  ALLOWLIST: '허용 목록',
  DENYLIST: '차단 목록',
};
