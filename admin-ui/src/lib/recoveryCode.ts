/**
 * 복구 코드 입력을 백엔드 권위 포맷(Base32 4-4, "XXXX-XXXX")으로 정형한다.
 *
 * 백엔드(RecoveryCodeService)는 알파벳 ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (혼동 문자
 * I/O/0/1 제외)로 생성하고, consume() 의 normalize 는 대시를 제거하지 않으므로 검증이
 * 매칭되려면 입력이 정확히 대문자 "XXXX-XXXX" 여야 한다. 이 함수가 그 형태를 보장한다.
 *
 * - 대문자화 후 허용 문자만 통과 → 소문자·기호·공백·혼동 문자(0,1,I,O) 제거
 * - 8자로 제한, 4자 초과 시 4번째 뒤 대시 1개 삽입
 * - 대시는 길이에서 파생되므로 백스페이스 시 자연히 사라진다
 */
export function formatRecoveryCode(input: string): string {
  // 백엔드 알파벳과 정확히 매칭: A-Z (I, O 제외) + 2-9 (0, 1 제외)
  const cleaned = input
    .toUpperCase()
    .replace(/[^ABCDEFGHJKLMNPQRSTUVWXYZ2-9]/g, '')
    .slice(0, 8);
  return cleaned.length > 4 ? cleaned.slice(0, 4) + '-' + cleaned.slice(4) : cleaned;
}
