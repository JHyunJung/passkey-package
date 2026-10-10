import { useEffect, useRef, ReactNode } from 'react';
import { useBlocker } from 'react-router-dom';
import { Dialog } from './Dialog';

export function useUnsavedGuard(dirty: boolean): ReactNode {
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    dirty && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search));
  const stayRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  if (blocker.state !== 'blocked') return null;
  return (
    <Dialog open onClose={() => blocker.reset?.()} role="alertdialog" title="저장하지 않은 변경이 있습니다" initialFocusRef={stayRef}
      footer={<>
        <button ref={stayRef} type="button" className="btn" onClick={() => blocker.reset?.()}>머무르기</button>
        <button type="button" className="btn btn--danger-solid" onClick={() => blocker.proceed?.()}>이동</button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>이동하면 변경 사항이 사라집니다.</p>
    </Dialog>
  );
}
