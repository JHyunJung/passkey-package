import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded font-medium leading-tight tracking-[-0.011em] transition-colors disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-none focus-visible:shadow-focus',
  {
    variants: {
      variant: {
        default: 'border border-border bg-surface text-text shadow-xs hover:bg-surface-3',
        primary: 'border border-accent bg-accent text-accent-fg shadow-xs hover:bg-accent-hover active:bg-accent-press',
        danger: 'border border-danger bg-danger text-white shadow-xs hover:brightness-[0.92]',
        ghost: 'bg-transparent hover:bg-surface-3',
        outline: 'border border-border bg-transparent hover:bg-surface-2 hover:border-border-strong',
      },
      size: {
        xs: 'px-1.5 py-0.5 text-[11px] rounded-sm',
        sm: 'px-2 py-1 text-xs rounded-sm',
        default: 'px-3 py-1.5 text-[13px]',
        lg: 'px-4 py-2 text-sm',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />;
  }
);
Button.displayName = 'Button';

export { buttonVariants };
