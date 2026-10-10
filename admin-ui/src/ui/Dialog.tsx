import { useEffect, useId, useRef, ReactNode, RefObject, KeyboardEvent } from 'react';
import { cn } from '@/lib/cn';

type Props = {
  open: boolean; onClose: () => void; title?: ReactNode; sub?: ReactNode; children?: ReactNode;
  footer?: ReactNode; footerStart?: ReactNode; wide?: boolean | 'x'; dismissible?: boolean; closeOnScrim?: boolean;
  initialFocusRef?: RefObject<HTMLElement>; role?: 'dialog' | 'alertdialog'; ariaLabel?: string;
};
const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Dialog({ open, onClose, title, sub, children, footer, footerStart, wide, dismissible = true, closeOnScrim = true, initialFocusRef, role = 'dialog', ariaLabel }: Props) {
  const titleId = useId();
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open || !dismissible) return;
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, dismissible, onClose]);

  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const target = initialFocusRef?.current ?? boxRef.current?.querySelector<HTMLElement>(FOCUSABLE);
    target?.focus();
    return () => { prev?.focus?.(); };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  function trap(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key !== 'Tab' || !boxRef.current) return;
    const els = Array.from(boxRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (els.length === 0) return;
    const first = els[0], last = els[els.length - 1];
    if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  }

  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && dismissible && closeOnScrim) onClose(); }}>
      <div ref={boxRef} className={cn('dialog', wide === true && 'dialog--wide', wide === 'x' && 'dialog--xwide')} role={role} aria-modal="true"
        aria-labelledby={title ? titleId : undefined} aria-label={title ? undefined : ariaLabel} onKeyDown={trap}>
        {(title || sub) && (
          <div className="dialog__head">
            {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
            {sub && <div className="dialog__sub">{sub}</div>}
          </div>
        )}
        {children && <div className="dialog__body">{children}</div>}
        {(footer || footerStart) && (
          <div className="dialog__foot">
            {footerStart && <div className="dialog__foot-start">{footerStart}</div>}
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
