import { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { label: string; icon: ReactNode; size?: 'sm' | 'md'; tone?: 'default' | 'danger' };

export function IconButton({ label, icon, size = 'md', tone = 'default', className, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button type={type} aria-label={label} title={label} className={cn('icon-btn', size === 'sm' && 'icon-btn--sm', tone === 'danger' && 'icon-btn--danger', className)} {...rest}>
      {icon}
    </button>
  );
}
