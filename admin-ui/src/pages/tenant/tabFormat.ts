// admin-ui/src/pages/tenant/tabFormat.ts
// Tenant 탭(API 키·Credentials·감사 로그·퍼널) 공용 표시 헬퍼. 시간대는 KST 고정.
const TZ = 'Asia/Seoul';
const DATE = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }); // YYYY-MM-DD
const HM = new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hour12: false });
const HMS = new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });

function fmtWith(iso: string | null | undefined, f: (d: Date) => string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : f(d);
}

export const ymd = (iso: string | null | undefined) => fmtWith(iso, (d) => DATE.format(d));
export const ymdHm = (iso: string | null | undefined) => fmtWith(iso, (d) => `${DATE.format(d)} ${HM.format(d)}`);
export const hms = (iso: string | null | undefined) => fmtWith(iso, (d) => HMS.format(d));
export const ymdHms = (iso: string | null | undefined) => fmtWith(iso, (d) => `${DATE.format(d)} ${HMS.format(d)}`);

export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (isNaN(t)) return iso;
  const m = Math.floor((now - t) / 60_000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  return `${Math.floor(d / 30)}개월 전`;
}

export const fmtNum = (n: number) => n.toLocaleString('ko-KR');
