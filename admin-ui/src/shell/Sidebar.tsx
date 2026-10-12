import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview } from '@/api/auditChainMonitor';
import { licenseApi } from '@/api/license';
import { isPlatform } from '@/me/roles';
import { cn } from '@/lib/cn';
import { kstTime } from '@/lib/kst';
import { Button } from '@/ui';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';

type TopRoute = 'tenants' | 'activity' | 'audit-chain' | 'settings' | 'license';
type Item = { id: TopRoute; label: string; icon: keyof typeof Icons; tag?: string };
type ChainStatus = 'checking' | 'ok' | 'unknown';

export function timeAgo(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  return `${Math.floor(mins / 60)}시간 전`;
}

export function Sidebar({ me, currentRoute, onNavigate }: { me: Me; currentRoute: AppRoute; onNavigate: (r: AppRoute) => void }) {
  const platform = isPlatform(me);
  const [chain, setChain] = useState<ChainOverview | null>(null);
  const [chainStatus, setChainStatus] = useState<ChainStatus>('checking');
  const [lastCheckedAt, setLastCheckedAt] = useState<string | null>(null);
  const [checkStart, setCheckStart] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  const [mode, setMode] = useState<'saas' | 'onprem' | null>(null);
  const alive = useRef(true);
  const inFlight = useRef(false); // 폴링은 진행 중인 조회를 대체하지 않는다(수동 다시 시도만 대체)
  const seq = useRef(0); // 가장 나중에 시작한 조회만 상태를 바꾼다(겹친 조회의 역순 완료 방지)

  useEffect(() => { licenseApi.get().then((v) => setMode(v.deploymentMode)).catch(() => setMode('saas')); }, []);

  // 실패를 "확인 중"으로 감추지 않는다(H1·H9): 실패하면 확인 불가 + 다시 시도.
  const loadChain = useCallback((showChecking: boolean) => {
    if (showChecking) { const t = Date.now(); setChainStatus('checking'); setCheckStart(t); setNow(t); }
    if (!showChecking && inFlight.current) return;
    const mine = ++seq.current;
    inFlight.current = true;
    auditChainMonitorApi.overview(24)
      .then((o) => {
        if (!o?.totals) throw new Error('empty overview');
        if (!alive.current || mine !== seq.current) return;
        setChain(o);
        setLastCheckedAt(new Date().toISOString());
        setChainStatus('ok');
      })
      .catch(() => { if (alive.current && mine === seq.current) setChainStatus('unknown'); })
      .finally(() => { if (mine === seq.current) inFlight.current = false; });
  }, []);

  useEffect(() => {
    if (!platform) return; // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다
    alive.current = true;
    loadChain(false);
    const id = setInterval(() => loadChain(false), 30_000);
    return () => { alive.current = false; clearInterval(id); };
  }, [platform, loadChain]);

  // 검증 중일 때만 경과 초를 갱신한다.
  useEffect(() => {
    if (!platform || chainStatus !== 'checking') return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [platform, chainStatus]);

  const groups = useMemo(() => {
    const admin: Item[] = [{ id: 'settings', label: '설정', icon: 'Cog' }];
    if (!platform) return [{ label: '관리', items: admin }];
    const plat: Item[] = [
      { id: 'tenants', label: 'Tenants', icon: 'Building' },
      { id: 'activity', label: '활동', icon: 'Activity' },
      { id: 'audit-chain', label: '감사 체인', icon: 'Link' },
      ...(mode === 'onprem' ? [{ id: 'license' as const, label: '라이선스', icon: 'Key' as const, tag: '온프렘' }] : []),
    ];
    return [{ label: '플랫폼', items: plat }, { label: '관리', items: admin }];
  }, [platform, mode]);

  const tampered = chain?.totals?.tenantsTampered ?? 0;
  const activeName = currentRoute.name === 'tenant' ? 'tenants' : currentRoute.name;
  const checkingSecs = Math.max(0, Math.floor((now - checkStart) / 1000));

  return (
    <aside className="sidebar" aria-label="주 메뉴">
      <div className="sidebar__brand">
        <img src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="sidebar__env">Passkey Admin</div>
      </div>
      {groups.map((g) => (
        <div key={g.label} className="sidebar__group">
          <span className="sidebar__label">{g.label}</span>
          {g.items.map((it) => {
            const I = Icons[it.icon] as React.ComponentType<{ size?: number }>;
            const active = activeName === it.id;
            return (
              <button key={it.id} type="button" className={cn('nav-item', active && 'nav-item--active')} aria-current={active ? 'page' : undefined} onClick={() => onNavigate({ name: it.id })}>
                <I size={18} />
                <span>{it.label}</span>
                {it.tag && <span className="nav-item__tag">{it.tag}</span>}
                {it.id === 'audit-chain' && tampered > 0 && <span className="nav-item__count">위변조 {tampered}</span>}
              </button>
            );
          })}
        </div>
      ))}
      <div className="sidebar__foot">
        {platform && (chainStatus === 'unknown' ? (
          <div className="chain-card chain-card--warn">
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 4 }}>
              {/* 상태 변화(제목)만 알린다. */}
              <span className="chain-card__title" role="status">검증 상태 확인 불가</span>
              <span className="chain-card__sub">{lastCheckedAt ? `서버 응답이 없습니다 · ${kstTime(lastCheckedAt)} 마지막 확인` : '서버 응답이 없습니다'}</span>
              <Button size="sm" onClick={() => loadChain(true)} style={{ alignSelf: 'flex-start' }}>다시 시도</Button>
            </span>
          </div>
        ) : (
          <button type="button" className={cn('chain-card', chainStatus === 'checking' && 'chain-card--pending', chainStatus === 'ok' && tampered > 0 && 'chain-card--bad')} onClick={() => onNavigate({ name: 'audit-chain' })}>
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 2 }}>
              {chainStatus === 'checking' ? (<><span className="chain-card__title">검증 중…</span><span className="chain-card__sub" aria-live="off">{checkingSecs}초째 확인 중</span></>)
                : tampered > 0 ? (<><span className="chain-card__title">위변조 의심 · {tampered} tenant</span><span className="chain-card__sub">즉시 확인이 필요합니다</span></>)
                : chain ? (<><span className="chain-card__title">AUDIT CHAIN OK</span><span className="chain-card__sub">마지막 검증 · {timeAgo(chain.verifiedAt)} · {chain.totals.verifiedRows.toLocaleString()} 행</span></>)
                : null}
            </span>
          </button>
        ))}
        <span className="sidebar__copy">© Crosscert</span>
      </div>
    </aside>
  );
}
