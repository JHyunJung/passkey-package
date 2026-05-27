import { NavLink } from 'react-router-dom';
import { BrandMark, Building, Key, Receipt, Activity } from './Icons';
import { useMe } from '../me/MeContext';

const PLATFORM_NAV = [
  { to: '/tenants',  label: 'Tenants',      icon: Building },
  { to: '/keys',     label: 'Signing Keys', icon: Key },
  { to: '/mds',      label: 'MDS',          icon: Activity },
  { to: '/audit',    label: 'Audit Log',    icon: Receipt },
];

const rpAdminNav = (tenantId: string) => [
  { to: `/tenants/${tenantId}`, label: 'My Tenant', icon: Building },
];

export default function Sidebar() {
  const { me } = useMe();
  const nav = me?.role === 'RP_ADMIN' && me.tenantId
    ? rpAdminNav(me.tenantId)
    : (me ? PLATFORM_NAV : []);

  return (
    <aside style={{
      gridArea: 'sidebar',
      background: 'var(--surface)',
      borderRight: '1px solid var(--border)',
      display: 'flex',
      flexDirection: 'column',
      position: 'sticky',
      top: 0,
      height: '100vh',
      overflow: 'hidden',
    }}>
      <div style={{
        padding: '16px 18px',
        borderBottom: '1px solid var(--border)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}>
        <BrandMark size={26} />
        <div className="stack-1" style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 13, lineHeight: 1.2, letterSpacing: '-0.01em' }}>Passkey Admin</div>
          <div style={{ fontSize: 11, color: 'var(--text-mute)', lineHeight: 1.2 }}>Crosscert · prod</div>
        </div>
      </div>
      <nav style={{ flex: 1, padding: '8px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        {nav.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            style={({ isActive }) => ({
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '8px 12px',
              borderRadius: 'var(--radius)',
              color: isActive ? 'var(--accent)' : 'var(--text-soft)',
              background: isActive ? 'var(--accent-soft)' : 'transparent',
              textDecoration: 'none',
              fontSize: 13,
              fontWeight: 500,
              transition: 'background var(--dur) var(--ease-out), color var(--dur) var(--ease-out)',
            })}
          >
            <Icon size={16} />
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
