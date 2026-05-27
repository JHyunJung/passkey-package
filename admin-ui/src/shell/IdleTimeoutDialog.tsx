import { useEffect, useRef, useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

const IDLE_MS = 30 * 60 * 1000; // 30 min
const COUNTDOWN_S = 60;

const ACTIVITY_EVENTS: (keyof WindowEventMap)[] = ['mousemove', 'keydown', 'scroll', 'click'];

export function IdleTimeoutDialog() {
  const [open, setOpen] = useState(false);
  const [remaining, setRemaining] = useState(COUNTDOWN_S);
  const lastActiveRef = useRef(Date.now());
  const checkIntervalRef = useRef<number | undefined>(undefined);
  const tickIntervalRef = useRef<number | undefined>(undefined);
  const loggingOutRef = useRef(false);
  const openRef = useRef(false);

  // keep openRef in sync with open state (accessible in closures)
  openRef.current = open;

  function clearTick() {
    if (tickIntervalRef.current !== undefined) {
      window.clearInterval(tickIntervalRef.current);
      tickIntervalRef.current = undefined;
    }
  }

  function logout() {
    if (loggingOutRef.current) return;
    loggingOutRef.current = true;
    clearTick();
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    }).finally(() => {
      window.location.href = '/admin/login';
    });
  }

  function startCountdown() {
    setOpen(true);
    setRemaining(COUNTDOWN_S);
    clearTick();
    tickIntervalRef.current = window.setInterval(() => {
      setRemaining((r) => {
        if (r <= 1) {
          logout();
          return 0;
        }
        return r - 1;
      });
    }, 1000);
  }

  function extend() {
    lastActiveRef.current = Date.now();
    setOpen(false);
    clearTick();
    loggingOutRef.current = false;
  }

  useEffect(() => {
    const bump = () => {
      lastActiveRef.current = Date.now();
    };

    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, bump));

    // poll every 10s — same pattern as IdleTimeout.tsx
    checkIntervalRef.current = window.setInterval(() => {
      if (!openRef.current && Date.now() - lastActiveRef.current > IDLE_MS) {
        startCountdown();
      }
    }, 10_000);

    return () => {
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, bump));
      if (checkIntervalRef.current !== undefined) {
        window.clearInterval(checkIntervalRef.current);
      }
      clearTick();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) extend();
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>세션이 곧 만료됩니다</DialogTitle>
          <DialogDescription>
            30분 동안 활동이 없어 자동 로그아웃됩니다. 계속 사용하려면 [세션 연장]을 클릭하세요.
          </DialogDescription>
        </DialogHeader>
        <DialogBody>
          <div className="flex items-center gap-3">
            <div className="text-[28px] font-semibold tabular-nums tracking-[-0.022em]">
              {remaining}s
            </div>
            <div className="flex-1 h-2 bg-surface-3 rounded-pill overflow-hidden">
              <div
                className="h-full bg-accent transition-all"
                style={{ width: `${(remaining / COUNTDOWN_S) * 100}%` }}
              />
            </div>
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="ghost" onClick={logout}>
            지금 로그아웃
          </Button>
          <Button variant="primary" onClick={extend}>
            세션 연장
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
