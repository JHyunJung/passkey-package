import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import {
  Badge, Button, Card, CardFooter, DataTable, Dialog, EmptyState, FilterChip, Segmented, Switch, useToast,
  type Column,
} from '@/ui';
import { auditApi } from '@/api/audit';
import { copyToClipboard } from '@/lib/clipboard';
import { downloadCsv } from '@/lib/csvExport';
import type { Tenant, AuditEvent, ChainVerifyResult } from '@/api/designTypes';
import { fmtNum, hms, timeAgo, ymdHms } from './tabFormat';
import { WINDOWS, type WindowKey, dayLabel, diffValue, eventTone, isFailure, payloadDiff, windowRange } from './auditHelpers';

const PAGE_SIZE = 50;
const tail = (s: string | null | undefined, n: number) => (s ? s.slice(-n) : '');
const pretty = (e: AuditEvent) => JSON.stringify(e.payload ?? {}, null, 2);

// ── AuditTab ──────────────────────────────────────────────────────────────────

export default function AuditTab({ tenant, isPlatformOperator = false }: { tenant: Tenant; isPlatformOperator?: boolean }) {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [filter, setFilter] = useState<Set<string>>(new Set());
  const [onlyFailures, setOnlyFailures] = useState(false);
  const [win, setWin] = useState<WindowKey>('24h');
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [verifyResult, setVerifyResult] = useState<ChainVerifyResult | null>(null);
  const [showVerify, setShowVerify] = useState(false);
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
        if (!cancelled) toast({ kind: 'err', title: 'audit 로드 실패', message: e instanceof Error ? e.message : String(e) });
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, win]);

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
      if (gen === genRef.current) toast({ kind: 'err', title: 'audit 로드 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      if (gen === genRef.current) {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    }
  }

  // 선택된 유형은 기간을 바꿔 목록에서 사라져도 칩으로 남긴다(해제 가능해야 함)
  const types = useMemo(() => Array.from(new Set([...items.map((e) => e.eventType), ...filter])), [items, filter]);
  const filtered = useMemo(
    () => items.filter((e) => (filter.size === 0 || filter.has(e.eventType)) && (!onlyFailures || isFailure(e))),
    [items, filter, onlyFailures],
  );

  function toggleType(t: string) {
    const next = new Set(filter);
    if (next.has(t)) next.delete(t); else next.add(t);
    setFilter(next);
  }

  async function copyJson(e: AuditEvent) {
    if (await copyToClipboard(pretty(e))) toast({ kind: 'ok', title: 'JSON 을 복사했습니다.' });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  function exportCsv() {
    if (filtered.length === 0) return;
    downloadCsv(
      `audit-${tenant.slug}-${new Date().toISOString().slice(0, 10)}.csv`,
      ['ts', 'eventType', 'actorType', 'actorId', 'subjectType', 'subjectId', 'payload'],
      filtered.map((e) => [e.ts, e.eventType, e.actorType, e.actorId ?? '', e.subjectType ?? '', e.subjectId ?? '', JSON.stringify(e.payload ?? {})]),
    );
  }

  async function handleVerifyRun() {
    try {
      // 서버 /chain/verify 는 기간을 받지 않고 항상 전체 체인을 검증한다.
      const result = await auditApi.verify(tenant.id);
      setVerifyResult(result);
      setShowVerify(false);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'chain 검증 실패', message: e instanceof Error ? e.message : String(e) });
      setShowVerify(false);
      throw e; // 다이얼로그가 running 상태를 끝내도록
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

  const countText = total !== undefined ? `${WINDOWS[win].long} · ${fmtNum(total)}건` : `최근 ${fmtNum(items.length)}건`;

  return (
    <div className="stack-6">
      {isPlatformOperator && <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} />}

      <Card>
        <div className="card__head">
          <div className="row" style={{ gap: 6 }}>
            {types.map((t) => <FilterChip key={t} active={filter.has(t)} onClick={() => toggleType(t)}>{t}</FilterChip>)}
            {filter.size > 0 && <Button variant="ghost" size="sm" onClick={() => setFilter(new Set())}>전체 해제</Button>}
          </div>
          <div className="spacer" />
          <Switch checked={onlyFailures} onChange={setOnlyFailures} label="실패만" />
          <Segmented ariaLabel="기간" value={win} onChange={setWin}
            options={(Object.keys(WINDOWS) as WindowKey[]).map((k) => ({ value: k, label: WINDOWS[k].label }))} />
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={filtered.length === 0}>내보내기</Button>
        </div>

        {loading ? (
          <p className="muted" style={{ padding: '40px 20px', margin: 0, textAlign: 'center', fontSize: 13 }}>불러오는 중…</p>
        ) : (
          <DataTable
            columns={columns}
            rows={filtered}
            rowKey={(e) => e.id}
            groupBy={(e) => dayLabel(e.ts)}
            onRowClick={(e) => setExpanded((k) => (k === e.id ? null : e.id))}
            expandedKey={expanded}
            renderExpanded={(e) => <ExpandedEvent e={e} onCopy={() => void copyJson(e)} onOpen={() => setOpen(e)} />}
            empty={<EmptyState compact title={filter.size > 0 || onlyFailures ? '조건에 맞는 이벤트가 없습니다.' : '이 기간에 감사 이벤트가 없습니다.'} />}
          />
        )}

        <CardFooter>
          <span className="hint" style={{ margin: 0 }}>{countText}</span>
          <div className="spacer" />
          {hasMore && <Button size="sm" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? '불러오는 중…' : '더 보기'}</Button>}
        </CardFooter>
      </Card>

      {isPlatformOperator && <ChainVerifyDialog open={showVerify} onClose={() => setShowVerify(false)} onRun={handleVerifyRun} />}
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

function ChainVerifyCard({ onOpen, result }: { onOpen: () => void; result: ChainVerifyResult | null }) {
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
            <h2 className="card__title">Audit Hash Chain 검증</h2>
            {result && (result.intact
              ? <Badge tone="ok">위변조 0건</Badge>
              : <Badge tone="bad">위변조 {result.tamperedEntryIds.length}건</Badge>)}
            <Badge tone="plat" dot={false}>PLATFORM 전용</Badge>
          </div>
          <p className="card__sub">이 tenant 의 감사 로그 행이 hash 로 연결되어 변경·삭제되지 않았는지 검증합니다.</p>
        </div>
        {result?.intact && <Button onClick={() => navigate('/audit-chain')}>보고서</Button>}
        <Button variant="primary" onClick={onOpen}>검증 실행</Button>
      </div>
    </Card>
  );
}

// ── Hash chain 검증 다이얼로그 (진행률) ────────────────────────────────────────

function ChainVerifyDialog({ open, onClose, onRun }: { open: boolean; onClose: () => void; onRun: () => Promise<void> }) {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);

  async function run() {
    setRunning(true);
    setProgress(0);
    // API 응답 전까지 진행 막대를 95% 까지 움직인다(서버는 진행률을 주지 않음).
    const tick = setInterval(() => {
      setProgress((p) => Math.min(95, p + 7 + Math.random() * 9));
    }, 90);
    try {
      await onRun();
      setProgress(100);
    } catch {
      // 오류 토스트는 부모가 띄운다
    } finally {
      clearInterval(tick);
      setRunning(false);
    }
  }
  const pct = Math.floor(progress);

  return (
    <Dialog open={open} onClose={onClose} dismissible={!running} title="Hash chain 검증" sub="읽기 전용 검증입니다. 데이터는 변경되지 않습니다."
      footer={<>
        <Button onClick={onClose} disabled={running}>취소</Button>
        <Button variant="primary" disabled={running} onClick={() => void run()}>{running ? `검증 중… ${pct}%` : '검증 실행'}</Button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        전체 체인 검증 — 이 tenant 의 감사 로그 전체를 처음부터 hash 로 다시 계산해 확인합니다. 기간은 지정하지 않습니다.
      </p>
      {running && (
        <div className="stack-2">
          <div className="row">
            <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>검증 중…</span>
            <div className="spacer" />
            <span className="mono hint" style={{ margin: 0 }}>{pct}%</span>
          </div>
          <div role="progressbar" aria-label="검증 진행률" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}
            style={{ height: 8, borderRadius: 4, background: 'var(--line-soft)', overflow: 'hidden' }}>
            <div style={{ width: `${pct}%`, height: 8, background: 'var(--brand-700)', transition: 'width 90ms linear' }} />
          </div>
        </div>
      )}
    </Dialog>
  );
}
