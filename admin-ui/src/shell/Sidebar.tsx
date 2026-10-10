import { useEffect, useMemo, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview } from '@/api/auditChainMonitor';
import { licenseApi } from '@/api/license';
import { isPlatform } from '@/me/roles';
import { cn } from '@/lib/cn';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';

type TopRoute = 'tenants' | 'activity' | 'audit-chain' | 'settings' | 'license';
type Item = { id: TopRoute; label: string; icon: keyof typeof Icons; tag?: string };

export function timeAgo(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  return `${Math.floor(mins / 60)}시간 전`;
}

export function Sidebar({ me, currentRoute, onNavigate }: { me: Me; currentRoute: AppRoute; onNavigate: (r: AppRoute) => void }) {
  const platform = isPlatform(me);
  const [chain, setChain] = useState<ChainOverview | null>(null);
  const [mode, setMode] = useState<'saas' | 'onprem' | null>(null);

  useEffect(() => { licenseApi.get().then((v) => setMode(v.deploymentMode)).catch(() => setMode('saas')); }, []);
  useEffect(() => {
    if (!platform) return; // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다
    let cancelled = false;
    const load = () => auditChainMonitorApi.overview(24).then((o) => { if (!cancelled) setChain(o); }).catch(() => {});
    load();
    const id = setInterval(load, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [platform]);

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
                {it.id === 'audit-chain' && tampered > 0 && <span className="nav-item__count">{tampered}</span>}
              </button>
            );
          })}
        </div>
      ))}
      <div className="sidebar__foot">
        {platform && (
          <button type="button" className={cn('chain-card', !chain?.totals && 'chain-card--pending', tampered > 0 && 'chain-card--bad')} onClick={() => onNavigate({ name: 'audit-chain' })}>
            <span className="chain-card__dot" />
            <span className="stack-1" style={{ gap: 2 }}>
              {!chain?.totals ? (<><span className="chain-card__title">AUDIT CHAIN</span><span className="chain-card__sub">확인 중…</span></>)
                : tampered > 0 ? (<><span className="chain-card__title">위변조 의심 · {tampered} tenant</span><span className="chain-card__sub">즉시 확인이 필요합니다</span></>)
                : (<><span className="chain-card__title">AUDIT CHAIN OK</span><span className="chain-card__sub">마지막 검증 · {timeAgo(chain.verifiedAt)} · {chain.totals.verifiedRows.toLocaleString()} 행</span></>)}
            </span>
          </button>
        )}
        <span className="sidebar__copy">© Crosscert</span>
      </div>
    </aside>
  );
}
