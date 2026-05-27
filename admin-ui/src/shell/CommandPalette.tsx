import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { Command } from 'cmdk';
import {
  Command as CommandIcon,
  Building2,
  Activity as ActivityIcon,
  ShieldCheck,
  Database,
  KeyRound,
  LogOut,
} from 'lucide-react';
import { useMe } from '@/me/MeContext';
import { api } from '@/api/client';
import { cn } from '@/lib/utils';

type Props = { open: boolean; onOpenChange: (v: boolean) => void };

export function CommandPalette({ open, onOpenChange }: Props) {
  const navigate = useNavigate();
  const { me } = useMe();
  const lastFocusedRef = useRef<HTMLElement | null>(null);

  // ⌘K / Ctrl+K toggle + Esc close
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        onOpenChange(!open);
        return;
      }
      if (open && e.key === 'Escape') {
        e.preventDefault();
        onOpenChange(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onOpenChange]);

  // Focus return — save the element that opened us, restore on close (WCAG 2.4.3)
  useEffect(() => {
    if (open) {
      lastFocusedRef.current = (document.activeElement as HTMLElement) ?? null;
      return;
    }
    const el = lastFocusedRef.current;
    if (el && typeof el.focus === 'function') {
      el.focus();
    }
    lastFocusedRef.current = null;
  }, [open]);

  function go(path: string) {
    navigate(path);
    onOpenChange(false);
  }

  async function logout() {
    try {
      await api.post('/admin/logout', {});
    } catch {
      /* ignore */
    }
    onOpenChange(false);
    navigate('/login');
  }

  if (!open || typeof document === 'undefined') return null;

  return createPortal(
    <>
      <div
        className="fixed inset-0 z-[80] bg-black/40 backdrop-blur-md"
        onClick={() => onOpenChange(false)}
        aria-hidden
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        className="fixed left-1/2 top-[20vh] -translate-x-1/2 z-[90] w-[min(560px,92vw)] rounded-xl border border-border bg-surface shadow-lg overflow-hidden"
      >
        <Command shouldFilter className="flex flex-col">
          <div className="flex items-center gap-2 px-3 border-b border-border-subtle">
            <CommandIcon className="h-4 w-4 text-text-mute" />
            <Command.Input
              autoFocus
              placeholder="Search pages, actions..."
              className="flex-1 py-3 bg-transparent text-[13px] text-text placeholder:text-text-faint outline-none"
            />
          </div>
          <Command.List className="max-h-[60vh] overflow-y-auto p-2">
            <Command.Empty className="px-3 py-4 text-[13px] text-text-mute">
              No results.
            </Command.Empty>

            <Command.Group
              heading="Navigate"
              className="text-[11px] font-semibold uppercase text-text-mute tracking-wider px-2 pt-2 pb-1"
            >
              <PalItem onSelect={() => go('/tenants')} icon={<Building2 className="h-4 w-4" />} label="Tenants" />
              {me?.role === 'PLATFORM_OPERATOR' && (
                <>
                  <PalItem onSelect={() => go('/activity')} icon={<ActivityIcon className="h-4 w-4" />} label="Activity" />
                  <PalItem onSelect={() => go('/audit')} icon={<ShieldCheck className="h-4 w-4" />} label="Audit Log" />
                  <PalItem onSelect={() => go('/keys')} icon={<KeyRound className="h-4 w-4" />} label="Signing Keys" />
                  <PalItem onSelect={() => go('/mds')} icon={<Database className="h-4 w-4" />} label="MDS" />
                </>
              )}
              {me?.role === 'RP_ADMIN' && me.tenantId && (
                <PalItem onSelect={() => go(`/tenants/${me.tenantId}`)} icon={<Building2 className="h-4 w-4" />} label="My Tenant" />
              )}
            </Command.Group>

            <Command.Group
              heading="Actions"
              className="text-[11px] font-semibold uppercase text-text-mute tracking-wider px-2 pt-3 pb-1"
            >
              <PalItem onSelect={logout} icon={<LogOut className="h-4 w-4 text-danger" />} label="Logout" />
            </Command.Group>
          </Command.List>
        </Command>
      </div>
    </>,
    document.body
  );
}

function PalItem({ onSelect, icon, label }: { onSelect: () => void; icon: React.ReactNode; label: string }) {
  return (
    <Command.Item
      onSelect={onSelect}
      className={cn(
        'flex items-center gap-2 px-2 py-1.5 rounded-sm cursor-pointer text-[13px] text-text',
        'data-[selected=true]:bg-surface-2 data-[selected=true]:text-text'
      )}
    >
      {icon}
      <span>{label}</span>
    </Command.Item>
  );
}
