import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import {
  Badge, Button, Card, CardFooter, DataTable, Dialog, EmptyState, FilterChip, LoadError, Segmented, Skeleton, Switch, useToast,
  type Column,
} from '@/ui';
import { auditApi } from '@/api/audit';
import { copyToClipboard } from '@/lib/clipboard';
import { downloadCsv } from '@/lib/csvExport';
import { describeError } from '@/lib/describeError';
import { kstToday } from '@/lib/kst';
import type { Tenant, AuditEvent, ChainVerifyResult } from '@/api/designTypes';
import { fmtNum, hms, timeAgo, ymdHm, ymdHms } from './tabFormat';
import {
  EVENT_GROUPS, WINDOWS, type EventGroup, type WindowKey, dayLabel, diffValue, eventGroup, eventTone, isFailure, payloadDiff, windowRange,
} from './auditHelpers';

const PAGE_SIZE = 50;
const tail = (s: string | null | undefined, n: number) => (s ? s.slice(-n) : '');
const pretty = (e: AuditEvent) => JSON.stringify(e.payload ?? {}, null, 2);

// ── AuditTab ──────────────────────────────────────────────────────────────────

export default function AuditTab({ tenant, isPlatformOperator = false }: { tenant: Tenant; isPlatformOperator?: boolean }) {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [groups, setGroups] = useState<Set<EventGroup>>(new Set());
  const [onlyFailures, setOnlyFailures] = useState(false);
  const [win, setWin] = useState<WindowKey>('24h');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);        // 다시 시도
  const [expanded, setExpanded] = useState<string | null>(null);
  const [verifyResult, setVerifyResult] = useState<ChainVerifyResult | null>(null);
  const [showVerify, setShowVerify] = useState(false);
  const [verifyStartedAt, setVerifyStartedAt] = useState<number | null>(null);
  const verifyingRef = useRef(false);
  const verifyGenRef = useRef(0); // 테넌트가 바뀌면 증가 — 이전 테넌트의 검증 응답 폐기용
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const rangeRef = useRef<{ from: string; to: string } | null>(null); // 더 보기도 같은 구간으로
  const genRef = useRef(0);       // 초기 로드마다 증가 — 늦게 온 더 보기 응답 폐기용
  const pageRef = useRef(0);
  const loadingMoreRef = useRef(false);
  const toast = useToast();

  useEffect(() => {
    let cancelled = false;
    const range = windowRange(WINDOWS[win].hours);
    rangeRef.current = range;
    genRef.current += 1;
    pageRef.current = 0;
    loadingMoreRef.current = false;
    setLoadingMore(false);
    setLoading(true);
    setLoadError(null);
    setExpanded(null);
    auditApi.list(tenant.id, 0, PAGE_SIZE, range)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
        pageRef.current = 0;
        setHasMore(res.items.length === PAGE_SIZE);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // 기간·테넌트가 바뀐 로드이므로 이전 행을 남기지 않는다 — 실패를 "이벤트 없음"으로 보이지 않게 오류를 남긴다.
        setItems([]);
        setTotal(undefined);
        setHasMore(false);
        setLoadError(e ?? new Error('unknown'));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, win, attempt]);

  // 테넌트가 바뀌면 이전 테넌트의 검증 상태·진행 중 응답을 버린다.
  useEffect(() => {
    return () => {
      verifyGenRef.current += 1;
      verifyingRef.current = false;
      setVerifyResult(null);
      setVerifyStartedAt(null);
      setShowVerify(false);
    };
  }, [tenant.id]);

  async function loadMore() {
    if (!rangeRef.current || loadingMoreRef.current) return;
    const gen = genRef.current;
    const next = pageRef.current + 1;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const res = await auditApi.list(tenant.id, next, PAGE_SIZE, rangeRef.current);
      if (gen !== genRef.current) return; // 기간·테넌트가 바뀜 — 이전 응답 폐기
      setItems((prev) => [...prev, ...res.items]);
      pageRef.current = next;
      setHasMore(res.items.length === PAGE_SIZE);
    } catch (e: unknown) {
      if (gen === genRef.current) {
        const info = describeError(e);
        toast({ kind: 'err', title: '감사 로그를 더 불러오지 못했습니다', message: `${info.title}. ${info.detail}` });
      }
    } finally {
      if (gen === genRef.current) {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    }
  }

  const filtering = groups.size > 0 || onlyFailures;
  const filtered = useMemo(
    () => items.filter((e) => (groups.size === 0 || groups.has(eventGroup(e.eventType))) && (!onlyFailures || isFailure(e))),
    [items, groups, onlyFailures],
  );

  function toggleGroup(g: EventGroup) {
    const next = new Set(groups);
    if (next.has(g)) next.delete(g); else next.add(g);
    setGroups(next);
  }
  function clearFilters() {
    setGroups(new Set());
    setOnlyFailures(false);
  }

  async function copyJson(e: AuditEvent) {
    if (await copyToClipboard(pretty(e))) toast({ kind: 'ok', title: 'JSON 을 복사했습니다.' });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  function exportCsv() {
    if (filtered.length === 0) return;
    downloadCsv(
      `audit-${tenant.slug}-${kstToday()}.csv`,
      ['ts', 'eventType', 'actorType', 'actorId', 'subjectType', 'subjectId', 'payload'],
      filtered.map((e) => [e.ts, e.eventType, e.actorType, e.actorId ?? '', e.subjectType ?? '', e.subjectId ?? '', JSON.stringify(e.payload ?? {})]),
    );
  }

  async function handleVerifyRun() {
    if (verifyingRef.current) return; // 연타 방지
    verifyingRef.current = true;
    const gen = verifyGenRef.current;
    setVerifyStartedAt(Date.now());
    try {
      // 서버 /chain/verify 는 기간을 받지 않고 항상 전체 체인을 검증한다. 진행률도 주지 않는다.
      const result = await auditApi.verify(tenant.id);
      if (gen !== verifyGenRef.current) return; // 테넌트가 바뀜 — 이전 응답 폐기
      setVerifyResult(result);
      setShowVerify(false);
    } catch (e: unknown) {
      if (gen !== verifyGenRef.current) return;
      const info = describeError(e);
      // 다이얼로그를 닫은 뒤 끝날 수도 있으므로 오류는 토스트로 알린다.
      toast({ kind: 'err', title: '체인 검증에 실패했습니다', message: `${info.title}. ${info.detail}` });
    } finally {
      // 테넌트가 바뀌었다면 정리 effect 가 이미 해제했고 새 검증이 돌고 있을 수 있어 건드리지 않는다.
      if (gen === verifyGenRef.current) {
        verifyingRef.current = false;
        setVerifyStartedAt(null);
      }
    }
  }

  const columns: Column<AuditEvent>[] = [
    {
      key: 'ts', header: '시각', width: 140,
      render: (e) => (
        <div className="stack-1" style={{ gap: 0 }}>
          <span style={{ fontSize: 13 }}>{hms(e.ts)}</span>
          <span className="hint" style={{ margin: 0 }}>{timeAgo(e.ts)}</span>
        </div>
      ),
    },
    { key: 'type', header: '이벤트', render: (e) => <Badge tone={eventTone(e)} dot={false} mono>{e.eventType}</Badge> },
    {
      key: 'actor', header: '행위자',
      render: (e) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="tag">{e.actorType}</span>
          {e.actorId && <span className="mono hint" style={{ margin: 0 }}>…{tail(e.actorId, 10)}</span>}
        </span>
      ),
    },
    {
      key: 'subject', header: '대상',
      render: (e) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="tag">{e.subjectType ?? '—'}</span>
          {e.subjectId && <span className="mono hint" style={{ margin: 0 }}>…{tail(e.subjectId, 12)}</span>}
        </span>
      ),
    },
    {
      key: 'detail', header: '상세 내용',
      render: (e) => <span className="mono hint truncate" style={{ display: 'inline-block', maxWidth: 280, margin: 0, verticalAlign: 'middle' }}>{JSON.stringify(e.payload)}</span>,
    },
    {
      key: 'chev', header: '펼치기', srOnlyHeader: true, width: 32,
      render: (e) => <span style={{ color: 'var(--mute)', display: 'inline-flex' }}>{expanded === e.id ? <Icons.ChevronUp size={14} /> : <Icons.ChevronDown size={14} />}</span>,
    },
  ];

  const failed = loadError !== null;
  const countText = failed
    ? '—'
    : filtering
      ? `이 페이지 ${fmtNum(items.length)}건 중 ${fmtNum(filtered.length)}건`
      : total !== undefined ? `${WINDOWS[win].long} · ${fmtNum(total)}건` : `최근 ${fmtNum(items.length)}건`;

  return (
    <div className="stack-6">
      {isPlatformOperator && <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} verifying={verifyStartedAt !== null} />}

      <Card>
        <div className="card__head">
          <div className="row" role="group" aria-label="이벤트 종류" style={{ gap: 6 }}>
            {EVENT_GROUPS.map((g) => (
              <FilterChip key={g.id} active={groups.has(g.id)} title={g.codes} onClick={() => toggleGroup(g.id)}>{g.label}</FilterChip>
            ))}
          </div>
          {filtering && <Button variant="ghost" size="sm" onClick={clearFilters}>필터 지우기</Button>}
          <div className="spacer" />
          <Switch checked={onlyFailures} onChange={setOnlyFailures} label="실패만" />
          <Segmented ariaLabel="기간" value={win} onChange={setWin}
            options={(Object.keys(WINDOWS) as WindowKey[]).map((k) => ({ value: k, label: WINDOWS[k].label }))} />
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={failed || loading || filtered.length === 0}>
            CSV 내보내기 (현재 페이지 {fmtNum(filtered.length)}건)
          </Button>
        </div>

        {loading ? (
          <Skeleton rows={6} label="감사 로그 불러오는 중" />
        ) : failed ? (
          <LoadError what="감사 로그" error={loadError} onRetry={() => setAttempt((n) => n + 1)} />
        ) : (
          <DataTable
            columns={columns}
            rows={filtered}
            rowKey={(e) => e.id}
            groupBy={(e) => dayLabel(e.ts)}
            onRowClick={(e) => setExpanded((k) => (k === e.id ? null : e.id))}
            expandedKey={expanded}
            renderExpanded={(e) => <ExpandedEvent e={e} onCopy={() => void copyJson(e)} onOpen={() => setOpen(e)} />}
            empty={<EmptyState compact title={filtering ? '조건에 맞는 이벤트가 없습니다.' : '이 기간에 감사 이벤트가 없습니다.'} />}
          />
        )}

        <CardFooter>
          <span className="hint" style={{ margin: 0 }}>{countText}</span>
          <div className="spacer" />
          {hasMore && !failed && !loading && <Button size="sm" disabled={loadingMore} aria-busy={loadingMore} onClick={() => void loadMore()}>더 보기</Button>}
        </CardFooter>
      </Card>

      {isPlatformOperator && (
        <ChainVerifyDialog open={showVerify} onClose={() => setShowVerify(false)} onRun={() => void handleVerifyRun()}
          startedAt={verifyStartedAt} tenantName={tenant.name} />
      )}
      {open && <AuditEventDialog event={open} onClose={() => setOpen(null)} onCopy={() => void copyJson(open)} />}
    </div>
  );
}

// ── 펼친 행: diff 칩 + payload + JSON 복사 + 상세 다이얼로그 ─────────────────

function ExpandedEvent({ e, onCopy, onOpen }: { e: AuditEvent; onCopy: () => void; onOpen: () => void }) {
  const diff = payloadDiff(e.payload);
  return (
    <div className="stack-3" style={{ paddingLeft: 140 }}>
      {diff.map((d) => (
        <div key={d.field} className="row" style={{ gap: 8, fontSize: 12, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ width: 96, color: 'var(--mute)' }}>{d.field}</span>
          <del className="tag" style={{ background: 'var(--bad-soft)', color: 'var(--bad-ink)' }}>{diffValue(d.from)}</del>
          <span aria-hidden="true">→</span>
          <ins className="tag" style={{ background: 'var(--ok-soft)', color: 'var(--ok-ink)', textDecoration: 'none' }}>{diffValue(d.to)}</ins>
        </div>
      ))}
      <div className="row" style={{ gap: 8 }}>
        <span className="t-over">payload</span>
        <div className="spacer" />
        <Button variant="ghost" size="sm" onClick={onCopy}>JSON 복사</Button>
        <Button size="sm" onClick={onOpen}>상세 다이얼로그</Button>
      </div>
      <pre className="mono" style={{ margin: 0, padding: '10px 12px', border: '1px solid var(--line)', borderRadius: 8, background: 'var(--surface)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap' }}>{pretty(e)}</pre>
    </div>
  );
}

// ── Audit Event 다이얼로그 (V2: 언제 · 누가 · 대상 · payload) ────────────────

function AuditEventDialog({ event, onClose, onCopy }: { event: AuditEvent; onClose: () => void; onCopy: () => void }) {
  const t = new Date(event.ts);
  return (
    <Dialog open onClose={onClose}
      title={<span className="row" style={{ gap: 8 }}><span>Audit Event</span><Badge tone={eventTone(event)} dot={false} mono>{event.eventType}</Badge></span>}
      footer={<Button onClick={onClose}>닫기</Button>}>
      <div className="stack-1">
        <span className="t-over">언제</span>
        <span style={{ fontSize: 13 }}>{ymdHms(event.ts)} KST</span>
        <span className="mono hint" style={{ margin: 0 }}>{isNaN(t.getTime()) ? event.ts : t.toISOString()}</span>
      </div>
      <div className="row" style={{ gap: 24, alignItems: 'flex-start' }}>
        <div className="stack-1">
          <span className="t-over">누가 (actor)</span>
          <span className="row" style={{ gap: 6, fontSize: 13 }}><span className="tag">{event.actorType}</span><span className="mono hint" style={{ margin: 0 }}>{event.actorId ?? '—'}</span></span>
        </div>
        <div className="stack-1">
          <span className="t-over">대상 (subject)</span>
          <span className="row" style={{ gap: 6, fontSize: 13 }}><span className="tag">{event.subjectType ?? '—'}</span><span className="mono hint" style={{ margin: 0 }}>{event.subjectId ?? '—'}</span></span>
        </div>
      </div>
      <div className="stack-2">
        <div className="row"><span className="t-over">payload</span><div className="spacer" /><Button variant="ghost" size="sm" onClick={onCopy}>JSON 복사</Button></div>
        <pre className="mono" style={{ margin: 0, padding: 12, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line-soft)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap' }}>{pretty(event)}</pre>
      </div>
    </Dialog>
  );
}

// ── 체인 검증 카드 (PLATFORM 전용) ─────────────────────────────────────────────

function ChainVerifyCard({ onOpen, result, verifying }: { onOpen: () => void; result: ChainVerifyResult | null; verifying: boolean }) {
  const navigate = useNavigate();
  const tampered = result != null && !result.intact;
  return (
    <Card>
      <div className="row" style={{ gap: 16, padding: '16px 20px' }}>
        <span aria-hidden="true" style={{ width: 40, height: 40, borderRadius: 10, background: tampered ? 'var(--bad-soft)' : 'var(--ok-soft)', color: tampered ? 'var(--bad)' : 'var(--ok)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>
          <Icons.Shield size={20} />
        </span>
        <div className="stack-1" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <div className="row" style={{ gap: 8 }}>
            <h2 className="card__title">감사 로그 위변조 검증</h2>
            {verifying ? <Badge>검증 중</Badge> : result && (result.intact
              ? <Badge tone="ok">위변조 0건</Badge>
              : <Badge tone="bad">위변조 {result.tamperedEntryIds.length}건</Badge>)}
            <Badge tone="plat" dot={false}>PLATFORM 전용</Badge>
          </div>
          <p className="card__sub">
            감사 로그 행이 해시로 이어져 바뀌거나 지워지지 않았는지 확인합니다.{result ? ` 마지막 검증 ${ymdHm(result.verifiedAt)} KST` : ''}
          </p>
        </div>
        {result?.intact && <Button onClick={() => navigate('/audit-chain')}>월간 보고서</Button>}
        <Button variant="primary" onClick={onOpen} disabled={verifying}>{verifying ? '검증 중…' : '검증 실행'}</Button>
      </div>
    </Card>
  );
}

// ── 해시 체인 검증 다이얼로그 — 진행률을 모르므로 퍼센트 없이 "검증 중…" + 경과 시간 ──

function ChainVerifyDialog({ open, onClose, onRun, startedAt, tenantName }: {
  open: boolean; onClose: () => void; onRun: () => void; startedAt: number | null; tenantName: string;
}) {
  const running = startedAt !== null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);
  const elapsed = startedAt !== null ? Math.max(0, Math.floor((now - startedAt) / 1000)) : 0;

  return (
    <Dialog open={open} onClose={onClose} title="해시 체인 검증" sub={`${tenantName}의 감사 로그 전체를 검증합니다. 읽기 전용이며 데이터는 바뀌지 않습니다.`}
      footer={<>
        <Button onClick={onClose}>{running ? '닫기 (계속 실행)' : '취소'}</Button>
        <Button variant="primary" disabled={running} onClick={onRun}>{running ? '검증 중…' : '검증 실행'}</Button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        전체 체인 검증 — 처음부터 해시를 다시 계산해 확인합니다. 기간은 지정하지 않습니다.
      </p>
      {startedAt !== null && (
        <div className="stack-2">
          <div className="row">
            <strong style={{ fontSize: 13 }} role="status" aria-live="polite">검증 중…</strong>
            <div className="spacer" />
            {/* 경과 시간은 매초 바뀌므로 aria-live 밖에 둔다 */}
            <span className="hint" style={{ margin: 0 }}>시작 {hms(new Date(startedAt).toISOString())} · 경과 {elapsed}초</span>
          </div>
          <div className="indet" role="progressbar" aria-label="검증 진행 중" />
        </div>
      )}
      <p className="hint" style={{ margin: 0 }}>진행률을 알 수 없어 퍼센트를 표시하지 않습니다. 창을 닫아도 검증은 계속되고 결과는 위 카드에 표시됩니다.</p>
    </Dialog>
  );
}
