import { useEffect, useRef, ReactNode } from 'react';
import { useBlocker } from 'react-router-dom';
import { Dialog } from './Dialog';

export type UnsavedGuardCopy = { title?: string; body?: string };

/** dirty 동안 라우트 이동(검색 파라미터만 바뀌는 이동 포함)과 새로고침/탭 닫기를 막는다. copy 로 확인 문구를 바꿀 수 있다. */
export function useUnsavedGuard(dirty: boolean, copy: UnsavedGuardCopy = {}): ReactNode {
  const title = copy.title ?? '저장하지 않은 변경이 있습니다';
  const body = copy.body ?? '이동하면 변경 사항이 사라집니다.';
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
    <Dialog open onClose={() => blocker.reset?.()} role="alertdialog" title={title} initialFocusRef={stayRef}
      footer={<>
        <button ref={stayRef} type="button" className="btn" onClick={() => blocker.reset?.()}>머무르기</button>
        <button type="button" className="btn btn--danger-solid" onClick={() => blocker.proceed?.()}>이동</button>
      </>}>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>{body}</p>
    </Dialog>
  );
}
