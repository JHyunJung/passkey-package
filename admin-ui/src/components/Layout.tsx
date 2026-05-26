import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';
import Header from './Header';
import CommandPalette from './CommandPalette';

export default function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false);
  return (
    <div className="app" style={{ gridTemplateAreas: '"sidebar content"' }}>
      <Sidebar />
      <div className="content" style={{ gridArea: 'content' }}>
        <Header onOpenPalette={() => setPaletteOpen(true)} />
        <main className="page">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </div>
  );
}
