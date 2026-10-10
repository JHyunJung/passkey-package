import { ReactNode } from 'react';

type Props = { count: number; summary?: ReactNode; onRevert: () => void; onSave: () => void; saving?: boolean; saveLabel?: string; revertLabel?: string };

export function SaveBar({ count, summary, onRevert, onSave, saving, saveLabel = '저장', revertLabel = '되돌리기' }: Props) {
  if (count <= 0) return null;
  return (
    <div className="savebar" role="region" aria-label="저장되지 않은 변경">
      <span className="savebar__dot" aria-hidden="true" />
      <strong className="savebar__title">저장하지 않은 변경 {count}건</strong>
      {summary && <span className="hint" style={{ margin: 0 }}>{summary}</span>}
      <div className="spacer" />
      <button type="button" className="btn" onClick={onRevert} disabled={saving}>{revertLabel}</button>
      <button type="button" className="btn btn--primary" onClick={onSave} disabled={saving}>{saving ? '저장 중…' : saveLabel}</button>
    </div>
  );
}
