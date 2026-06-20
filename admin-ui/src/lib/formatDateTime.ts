const FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/**
 * Convert an ISO-8601 timestamp to KST display format. The input's
 * offset is irrelevant — `new Date(iso)` parses the absolute instant,
 * and the formatter projects it to Asia/Seoul. Backend now emits
 * `+09:00`; both `+09:00` and `Z` render identically (no double-shift).
 * Em-dash for null/undefined/empty. Raw input on parse failure.
 *
 * Example: "2026-05-27T12:00:00+09:00" -> "2026. 05. 27. 12:00:00"
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (isNaN(date.getTime())) return iso;
  return FORMATTER.format(date);
}

/**
 * Date-only formatter for LocalDate values like "YYYY-MM-DD".
 * Renders the literal date without inventing a time component.
 * Use this for MDS `nextUpdate` and any other LocalDate fields —
 * passing such a value through formatDateTime() would interpret
 * the date as UTC midnight and display a spurious "09:00:00" in KST.
 *
 * Em-dash for null/undefined/empty. Raw input on parse failure.
 *
 * Example: "2026-05-27" -> "2026. 05. 27."
 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  // Accept YYYY-MM-DD literally; reformat to ko-KR style without TZ math.
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match) {
    return `${match[1]}. ${match[2]}. ${match[3]}.`;
  }
  return value;
}
