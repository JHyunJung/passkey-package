import { ApiError } from '@/api/types';

export type ErrorInfo = { title: string; detail: string; httpStatus?: number; traceId?: string; code?: string };

/** 사용자에게 보여 줄 쉬운 오류 문구. 원문은 detail 로만 남긴다. */
export function describeError(e: unknown): ErrorInfo {
  if (e instanceof ApiError) {
    const base = { httpStatus: e.httpStatus, traceId: e.traceId, code: e.code };
    if (e.httpStatus >= 500) return { ...base, title: '서버가 잠시 응답하지 않습니다', detail: '저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.' };
    if (e.httpStatus === 403) return { ...base, title: '권한이 없습니다', detail: '이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.' };
    if (e.httpStatus === 404) return { ...base, title: '찾을 수 없습니다', detail: '삭제되었거나 주소가 바뀌었을 수 있습니다.' };
    return { ...base, title: '요청을 처리하지 못했습니다', detail: e.serverMessage };
  }
  if (e instanceof TypeError) return { title: '네트워크에 연결할 수 없습니다', detail: '연결 상태를 확인한 뒤 다시 시도하세요.' };
  return { title: '알 수 없는 오류가 발생했습니다', detail: e instanceof Error ? e.message : String(e) };
}
