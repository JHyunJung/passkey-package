/**
 * 도움말 주소(스펙 §2 도움말 · §8 "문서·도움말 주소").
 * 서버 설정으로 받는 API 가 아직 없어 1단계는 빌드 상수로 둔다.
 * 값이 빈 문자열이면 그 항목(상단 바 도움말 메뉴, 로그인 화면 문의 링크)을 숨긴다 — 동작하지 않는 링크를 보여 주지 않는다.
 */
export const HELP_LINKS: { apiDocs: string; rpGuide: string; support: string } = {
  /** API 문서(새 창으로 연다) */
  apiDocs: '',
  /** RP 연동 가이드(새 창으로 연다) */
  rpGuide: '',
  /** 운영자 문의 — mailto: 또는 페이지 주소 */
  support: '',
};
