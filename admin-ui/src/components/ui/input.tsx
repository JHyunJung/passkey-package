import * as React from 'react';
import { cn } from '@/lib/utils';

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      ref={ref}
      className={cn(
        'w-full rounded border border-border bg-surface text-text text-[13px] px-2.5 py-1.5',
        'hover:border-border-strong focus:outline-none focus:border-accent focus:shadow-focus',
        'placeholder:text-text-faint disabled:bg-surface-2 disabled:text-text-mute disabled:cursor-not-allowed',
        'transition-colors',
        className
      )}
      {...props}
    />
  )
);
Input.displayName = 'Input';
