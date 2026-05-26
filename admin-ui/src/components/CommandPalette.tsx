import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Dialog from './Dialog';
import { Search, ChevronRight } from './Icons';

interface Props { open: boolean; onClose: () => void; }

const ITEMS = [
  { label: 'Tenants',     to: '/tenants' },
  { label: '신규 Tenant', to: '/tenants/new' },
  { label: 'API Keys',    to: '/api-keys' },
  { label: 'Signing Keys', to: '/keys' },
  { label: 'MDS Status',  to: '/mds' },
  { label: 'Audit Log',   to: '/audit' },
];

export default function CommandPalette({ open, onClose }: Props) {
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [idx, setIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQ('');
      setIdx(0);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  const filtered = useMemo(
    () => ITEMS.filter((i) => !q || i.label.toLowerCase().includes(q.toLowerCase())),
    [q]
  );

  useEffect(() => { setIdx(0); }, [q]);

  function go(to: string) {
    onClose();
    nav(to);
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setIdx((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setIdx((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (filtered[idx]) go(filtered[idx].to);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="검색" wide>
      <div className="row" style={{ gap: 10, marginBottom: 12 }}>
        <Search size={16} className="muted" />
        <input
          ref={inputRef}
          className="input"
          placeholder="페이지 검색…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={onKey}
          style={{ flex: 1 }}
        />
      </div>
      <div className="stack-1">
        {filtered.length === 0 && (
          <div className="muted" style={{ padding: '20px 0', textAlign: 'center' }}>일치하는 항목 없음</div>
        )}
        {filtered.map((item, i) => (
          <button
            key={item.to}
            className="row"
            onClick={() => go(item.to)}
            onMouseEnter={() => setIdx(i)}
            style={{
              width: '100%',
              padding: '8px 12px',
              border: 0,
              background: i === idx ? 'var(--accent-soft)' : 'transparent',
              color: i === idx ? 'var(--accent)' : 'var(--text)',
              borderRadius: 'var(--radius)',
              fontSize: 13,
              textAlign: 'left',
              cursor: 'pointer',
            }}
          >
            <span style={{ flex: 1 }}>{item.label}</span>
            <span className="mono muted" style={{ fontSize: 11 }}>{item.to}</span>
            <ChevronRight size={12} />
          </button>
        ))}
      </div>
    </Dialog>
  );
}
