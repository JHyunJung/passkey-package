import { Link, NavLink } from 'react-router-dom';
import {
  Building2,
  Activity as ActivityIcon,
  ShieldCheck,
  Database,
  KeyRound,
  Link2,
} from 'lucide-react';
import { useMe } from '@/me/MeContext';
import { useTweaks } from '@/app/providers/TweaksProvider';
import { cn } from '@/lib/utils';

type NavItem = { to: string; label: string; icon: React.ComponentType<{ className?: string }> };

const PLATFORM_NAV: NavItem[] = [
  { to: '/tenants',     label: 'Tenants',      icon: Building2 },
  { to: '/activity',    label: 'Activity',     icon: ActivityIcon },
  { to: '/audit',       label: 'Audit Log',    icon: ShieldCheck },
  { to: '/audit-chain', label: 'Chain',        icon: Link2 },
  { to: '/keys',        label: 'Signing Keys', icon: KeyRound },
  { to: '/mds',         label: 'MDS',          icon: Database },
];

const RP_NAV = (tenantId: string): NavItem[] => [
  { to: `/tenants/${tenantId}`, label: 'My Tenant', icon: Building2 },
];

export function Sidebar() {
  const { me, loading } = useMe();
  const { tweaks } = useTweaks();
  const iconOnly = tweaks.sidebar === 'icons';

  const items =
    loading || !me
      ? []
      : me.role === 'RP_ADMIN' && me.tenantId
        ? RP_NAV(me.tenantId)
        : PLATFORM_NAV;

  return (
    <aside className="border-r border-border-subtle bg-surface-2 flex flex-col sticky top-0 h-screen overflow-hidden">
      <Link
        to="/"
        className="px-4 py-4 border-b border-border-subtle flex items-center gap-2 text-sm font-semibold tracking-[-0.011em] text-text no-underline"
      >
        <ShieldCheck className="h-4 w-4 text-accent shrink-0" />
        {!iconOnly && <span>Passkey Admin</span>}
      </Link>
      <nav aria-label="Main navigation" className="flex-1 p-2 flex flex-col gap-0.5">
        {items.map((it) => (
          <NavLink
            key={it.to}
            to={it.to}
            end={it.to === '/tenants'}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2.5 px-2.5 py-1.5 rounded text-[13px] text-text-soft transition-colors no-underline',
                'hover:bg-surface-3 hover:text-text',
                isActive && 'bg-accent-soft text-accent font-medium',
              )
            }
          >
            <it.icon className="h-4 w-4 shrink-0" />
            {!iconOnly && <span>{it.label}</span>}
          </NavLink>
        ))}
      </nav>
      <div className="p-3 border-t border-border-subtle text-[11px] text-text-faint">
        {/* Audit Chain indicator placeholder — Phase B 에서 채움 */}
        <span className="opacity-50">chain status · pending</span>
      </div>
    </aside>
  );
}
