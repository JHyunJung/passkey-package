import { Toaster as Sonner } from 'sonner';

export function Toaster() {
  return (
    <Sonner
      position="bottom-right"
      richColors
      closeButton
      toastOptions={{
        classNames: {
          toast:
            'bg-surface border border-border text-text rounded-lg shadow-md p-3 text-[13px] font-sans',
          title: 'font-semibold text-text',
          description: 'text-text-mute text-xs mt-0.5',
          actionButton: 'bg-accent text-accent-fg rounded px-2 py-1 text-xs',
          cancelButton: 'bg-surface-3 text-text-soft rounded px-2 py-1 text-xs',
        },
      }}
    />
  );
}
