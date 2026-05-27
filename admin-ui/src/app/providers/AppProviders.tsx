import * as React from 'react';
import { TweaksProvider } from './TweaksProvider';
import { TooltipProvider } from '@/components/ui/tooltip';

export function AppProviders({ children }: { children: React.ReactNode }) {
  return (
    <TweaksProvider>
      <TooltipProvider delayDuration={150}>{children}</TooltipProvider>
    </TweaksProvider>
  );
}
