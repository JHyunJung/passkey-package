import { useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { CommandPalette } from './CommandPalette';
import { IdleTimeoutDialog } from './IdleTimeoutDialog';
import { TweaksPanel } from './TweaksPanel';

export function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(true);
      }
    };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);

  return (
    <div
      className="grid min-h-screen"
      style={{ gridTemplateColumns: 'var(--sidebar-w) 1fr' }}
    >
      <Sidebar />
      <div className="min-w-0 flex flex-col">
        <Header onOpenPalette={() => setPaletteOpen(true)} />
        <main className="flex-1">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
      <IdleTimeoutDialog />
      <TweaksPanel />
    </div>
  );
}
