// admin-ui/src/lib/kst.ts
// 화면 표시용 KST(Asia/Seoul) 포매터. 서버 시각은 ISO(Z 또는 +09:00) — 둘 다 같은 시각으로 해석한다.
const FMT = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Seoul',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hourCycle: 'h23',
});

function parts(iso: string): Record<string, string> | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const o: Record<string, string> = {};
  for (const p of FMT.formatToParts(d)) o[p.type] = p.value;
  return o;
}

/** KST 날짜 `YYYY-MM-DD`. */
export function kstDate(iso: string): string {
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day}` : iso;
}

/** KST 시각 `HH:mm`. */
export function kstTime(iso: string): string {
  const p = parts(iso);
  return p ? `${p.hour}:${p.minute}` : iso;
}

/** KST `YYYY-MM-DD HH:mm:ss`. */
export function kstDateTime(iso: string): string {
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}` : iso;
}

/** KST 날짜·시각 `YYYY-MM-DD HH:mm`. */
export function kstDateTimeMin(iso: string): string {
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}` : iso;
}

/** 지금의 KST 날짜 `YYYY-MM-DD`(파일 이름 등). */
export function kstToday(now: Date = new Date()): string {
  return kstDate(now.toISOString());
}

/** KST 기준 직전 달 1일 ~ 말일(`YYYY-MM-DD`). */
export function kstPrevMonthRange(now: Date = new Date()): { from: string; to: string } {
  const [y, m] = kstToday(now).split('-').map(Number);
  const py = m === 1 ? y - 1 : y;
  const pm = m === 1 ? 12 : m - 1;
  const last = new Date(Date.UTC(py, pm, 0)).getUTCDate(); // pm 은 1-based → Date.UTC(py, pm, 0) = 그 달 말일
  const mm = String(pm).padStart(2, '0');
  return { from: `${py}-${mm}-01`, to: `${py}-${mm}-${String(last).padStart(2, '0')}` };
}

/** UTC ISO `YYYY-MM-DDTHH:mm:ssZ`(밀리초 제거). */
export function utcIso(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

const DAY_MS = 86_400_000;

/** 피드 날짜 그룹 라벨. KST 기준 오늘/어제, 그 외 YYYY-MM-DD. KST 는 DST 가 없어 24h 빼기로 전날을 구한다. */
export function dayLabel(iso: string, now: Date): string {
  const key = kstDate(iso);
  if (key === kstDate(now.toISOString())) return '오늘';
  if (key === kstDate(new Date(now.getTime() - DAY_MS).toISOString())) return '어제';
  return key;
}

/** 입력 순서를 유지하며 같은 dayLabel 의 연속 구간을 하나의 그룹으로 묶는다(피드는 최신순). */
export function groupByDay<T>(items: T[], isoOf: (item: T) => string, now: Date): { label: string; items: T[] }[] {
  const groups: { label: string; items: T[] }[] = [];
  for (const it of items) {
    const label = dayLabel(isoOf(it), now);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.items.push(it);
    else groups.push({ label, items: [it] });
  }
  return groups;
}
