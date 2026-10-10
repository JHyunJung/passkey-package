import { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-solid';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';
export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize; icon?: ReactNode };

export function Button({ variant = 'secondary', size = 'md', icon, className, children, type = 'button', ...rest }: ButtonProps) {
  return (
    <button type={type} className={cn('btn', variant !== 'secondary' && `btn--${variant}`, size !== 'md' && `btn--${size}`, className)} {...rest}>
      {icon}
      {children}
    </button>
  );
}
