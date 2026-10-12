import { ApiError, RequestTimeoutError } from '@/api/types';

/**
 * 네트워크 오류(fetch 의 TypeError), 시간 초과(RequestTimeoutError) 또는 5xx — 요청이 처리되지 않았을 수 있어 "잠시 후 다시 시도"로 안내할 실패.
 * 비밀번호 찾기·가입 요청은 이 경우만 실패로 보여 주고, 4xx 는 계정 존재를 드러내지 않도록 성공과 같은 안내를 쓴다.
 */
export function isTransientError(e: unknown): boolean {
  if (e instanceof TypeError || e instanceof RequestTimeoutError) return true;
  return e instanceof ApiError && e.httpStatus >= 500;
}
