// V2 일러스트(ChatGPT Images, 흰 배경 플랫). 빈 상태·온보딩 전용, alt="" 로 사용.
import loginHero from './login-hero.webp';
import emptyTenants from './empty-tenants.webp';
import emptyCredentials from './empty-credentials.webp';
import allClear from './all-clear.webp';
import mfa from './mfa.webp';
import recoveryCodes from './recovery-codes.webp';
import noResults from './no-results.webp';
import apiKeyIssued from './api-key-issued.webp';
import signupSent from './signup-sent.webp';
import loadError from './load-error.webp';

export const illustrations = {
  loginHero, emptyTenants, emptyCredentials, allClear, mfa,
  recoveryCodes, noResults, apiKeyIssued, signupSent, loadError,
} as const;
export type IllustrationName = keyof typeof illustrations;
