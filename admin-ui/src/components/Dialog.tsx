import * as React from 'react';
import {
  Dialog as SDialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
  DialogFooter,
} from '@/components/ui/dialog';

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  sub?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  wide?: boolean;
  closeOnScrim?: boolean;
}

/**
 * @deprecated 기존 페이지 호환용 shim. 신규 코드는 `@/components/ui/dialog` 직접 사용.
 */
export default function Dialog({
  open, onClose, title, sub, children, footer, wide, closeOnScrim = true,
}: Props) {
  function handleOpenChange(v: boolean) {
    // scrim click triggers onOpenChange(false); only forward if closeOnScrim allows
    if (!v && closeOnScrim) onClose();
  }

  return (
    <SDialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        wide={wide}
        onEscapeKeyDown={closeOnScrim ? () => onClose() : (e) => e.preventDefault()}
        onPointerDownOutside={closeOnScrim ? undefined : (e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {sub && <DialogDescription>{sub}</DialogDescription>}
        </DialogHeader>
        <DialogBody>{children}</DialogBody>
        {footer && <DialogFooter>{footer}</DialogFooter>}
      </DialogContent>
    </SDialog>
  );
}
