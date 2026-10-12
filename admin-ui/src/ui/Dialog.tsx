import { useEffect, useId, useRef, ReactNode, RefObject } from 'react';
import { cn } from '@/lib/cn';
import { useFocusTrap } from './useFocusTrap';

type Props = {
  open: boolean; onClose: () => void; title?: ReactNode; sub?: ReactNode; children?: ReactNode;
  footer?: ReactNode; footerStart?: ReactNode; wide?: boolean | 'x'; dismissible?: boolean; closeOnScrim?: boolean;
  initialFocusRef?: RefObject<HTMLElement>; headerIcon?: ReactNode; role?: 'dialog' | 'alertdialog'; ariaLabel?: string;
};

export function Dialog({ open, onClose, title, sub, children, footer, footerStart, wide, dismissible = true, closeOnScrim = true, initialFocusRef, role = 'dialog', ariaLabel, headerIcon }: Props) {
  const titleId = useId();
  const boxRef = useRef<HTMLDivElement>(null);
  const trap = useFocusTrap(open, boxRef, initialFocusRef);

  useEffect(() => {
    if (!open || !dismissible) return;
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, dismissible, onClose]);

  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && dismissible && closeOnScrim) onClose(); }}>
      <div ref={boxRef} className={cn('dialog', wide === true && 'dialog--wide', wide === 'x' && 'dialog--xwide')} role={role} aria-modal="true"
        aria-labelledby={title ? titleId : undefined} aria-label={title ? undefined : ariaLabel} onKeyDown={trap}>
        {(title || sub) && (
          <div className="dialog__head" style={headerIcon ? { flexDirection: 'row', alignItems: 'center', gap: 16 } : undefined}>
            {headerIcon && <span style={{ flex: 'none' }}>{headerIcon}</span>}
            {headerIcon ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
                {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
                {sub && <div className="dialog__sub">{sub}</div>}
              </div>
            ) : (<>
              {title && <h3 className="dialog__title" id={titleId}>{title}</h3>}
              {sub && <div className="dialog__sub">{sub}</div>}
            </>)}
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
