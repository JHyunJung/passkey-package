import { useEffect, useState } from 'react';
import { illustrations } from '@/assets/illustrations';
import { describeError } from '@/lib/describeError';
import { eulReul } from '@/lib/josa';
import { copyToClipboard } from '@/lib/clipboard';
import { Button } from './Button';

type CopyState = 'idle' | 'ok' | 'fail';
const COPY_LABEL: Record<CopyState, string> = { idle: '오류 정보 복사', ok: '복사했습니다', fail: '복사하지 못했습니다' };

/** 로드 실패 공통 화면. 실패를 "없음"으로 보이지 않게 하고, 그 자리에서 다시 시도하게 한다. */
export function LoadError({ what, error, onRetry, retrying, compact }: { what: string; error: unknown; onRetry: () => void; retrying?: boolean; compact?: boolean }) {
  const info = describeError(error);
  const meta = [info.httpStatus ? `HTTP ${info.httpStatus}` : null, info.traceId ? `trace ${info.traceId}` : null].filter(Boolean).join(' · ');
  const [copyState, setCopyState] = useState<CopyState>('idle');

  // 복사 결과 문구는 2초 뒤 원래대로 돌아간다. 언마운트/재클릭 시 이전 타이머는 정리한다.
  useEffect(() => {
    if (copyState === 'idle') return;
    const t = setTimeout(() => setCopyState('idle'), 2000);
    return () => clearTimeout(t);
  }, [copyState]);

  const onCopy = async () => {
    const ok = await copyToClipboard([what, info.title, info.detail, meta].filter(Boolean).join('\n'));
    setCopyState(ok ? 'ok' : 'fail');
  };

  return (
    <div className="empty" role="alert" style={compact ? { padding: '20px 16px' } : undefined}>
      <img className="empty__art" src={illustrations.loadError} alt="" style={compact ? { width: 96, height: 96 } : undefined} />
      <p className="empty__title">{what}{eulReul(what)} 불러오지 못했습니다</p>
      <p className="empty__sub">{info.title}. {info.detail}</p>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }}>
        <Button variant="primary" onClick={onRetry} disabled={retrying}>{retrying ? '다시 시도 중…' : '다시 시도'}</Button>
        <Button variant="ghost" onClick={() => void onCopy()}>{COPY_LABEL[copyState]}</Button>
      </div>
      <span className="hint" aria-live="polite" style={{ margin: 0 }}>{copyState === 'idle' ? '' : COPY_LABEL[copyState]}</span>
      {meta && <p className="hint mono" style={{ margin: 0 }}>{meta}</p>}
    </div>
  );
}
