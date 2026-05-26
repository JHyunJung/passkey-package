import { useEffect, type ReactNode } from 'react';

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  sub?: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  closeOnScrim?: boolean;
}

export default function Dialog({
  open, onClose, title, sub, children, footer, wide, closeOnScrim = true,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div
      className="scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && closeOnScrim) onClose();
      }}
    >
      <div className={`dialog${wide ? ' dialog--wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <div className="dialog__head">
          <h3 id="dialog-title" className="dialog__title">{title}</h3>
          {sub && <div className="dialog__sub">{sub}</div>}
        </div>
        <div className="dialog__body">{children}</div>
        {footer && <div className="dialog__foot">{footer}</div>}
      </div>
    </div>
  );
}
