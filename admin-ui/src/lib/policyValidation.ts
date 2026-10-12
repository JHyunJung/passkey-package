// 보안 정책(설정 › 보안 정책) 입력 검증. 서버에 보내기 전에 형식 오류를 그 자리에서 알린다.

export const ORIGIN_FORMAT_ERROR = '형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com';
export const ORIGIN_DUPLICATE_ERROR = '이미 목록에 있습니다.';
export const ORIGIN_DEFAULT_PORT_ERROR = '기본 포트(https :443, http :80)는 적지 않습니다. 포트를 빼고 입력하세요.';
export const ORIGIN_LENGTH_ERROR = 'origin 은 256자 이하로 입력하세요.';
export const ORIGIN_COUNT_ERROR = 'origin 은 최대 64개까지 등록할 수 있습니다.';
export const TIMEOUT_ERROR = '1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.';

const ORIGIN_RE = /^(https?):\/\/([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)(?::(\d{1,5}))?$/i;
export const MAX_ORIGIN_LENGTH = 256;
export const MAX_ORIGINS = 64;
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);

/** 저장용 정규형. 앞뒤 공백을 지우고 스킴·호스트를 소문자로 맞춘다(origin 에는 경로가 없어 전체를 소문자로 바꿔도 같다). */
export function normalizeOrigin(value: string): string {
  return value.trim().toLowerCase();
}

/** CORS origin 검증. https://호스트[:포트] 만(경로·와일드카드·IPv6·유니코드 호스트 불가). http 는 로컬 개발 주소만. 통과하면 null. */
export function validateOrigin(value: string, existing: string[]): string | null {
  const v = value.trim();
  if (v.length > MAX_ORIGIN_LENGTH) return ORIGIN_LENGTH_ERROR;
  const m = ORIGIN_RE.exec(v);
  if (!m) return ORIGIN_FORMAT_ERROR;
  const scheme = m[1].toLowerCase();
  const host = m[2].toLowerCase();
  const port = m[3];
  if (scheme === 'http' && !LOCAL_HOSTS.has(host)) return ORIGIN_FORMAT_ERROR;
  if (port !== undefined) {
    if (port.length > 1 && port.startsWith('0')) return ORIGIN_FORMAT_ERROR;
    const n = Number(port);
    if (n < 1 || n > 65535) return ORIGIN_FORMAT_ERROR;
    if ((scheme === 'https' && n === 443) || (scheme === 'http' && n === 80)) return ORIGIN_DEFAULT_PORT_ERROR;
  }
  const key = normalizeOrigin(v);
  if (existing.some((o) => normalizeOrigin(o) === key)) return ORIGIN_DUPLICATE_ERROR;
  if (existing.length >= MAX_ORIGINS) return ORIGIN_COUNT_ERROR;
  return null;
}

/** 세션 idle timeout(분) 검증. 정수 1–1440(최대 24시간)만 통과. */
export function timeoutError(raw: string): string | null {
  const v = raw.trim();
  if (!/^\d+$/.test(v)) return TIMEOUT_ERROR;
  const n = Number(v);
  return n < 1 || n > 1440 ? TIMEOUT_ERROR : null;
}
