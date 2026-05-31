import { api } from './client';

/**
 * Self-service password reset (P1-6 / 그룹 A).
 *
 * PasswordResetController 는 ApiResponse envelope 이 아닌 raw {ok}/{reset} 를
 * 반환하므로 postRaw(envelope unwrap 안 함)를 쓴다. 두 엔드포인트 모두 permitAll.
 * confirm 의 400(만료/소비/약한 비번)은 postRaw 가 ApiError 로 throw(본문 message 보존).
 */
export const passwordResetApi = {
  request: (email: string) =>
    api.postRaw<{ ok: boolean }>('/admin/api/password-reset/request', { email }),
  confirm: (token: string, newPassword: string) =>
    api.postRaw<{ reset: boolean }>('/admin/api/password-reset/confirm', { token, newPassword }),
};
