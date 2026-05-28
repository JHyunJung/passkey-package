import { useEffect, useState, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { aaguidPolicyApi } from '../../api/aaguidPolicy';
import type { AaguidPolicyMode, AaguidPolicyView } from '../../api/types';
import { useToast } from '../../components/Toast';
import Switch from '../../components/Switch';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

interface ModeCardProps {
  value: AaguidPolicyMode;
  current: AaguidPolicyMode;
  title: string;
  sub: string;
  onClick: (v: AaguidPolicyMode) => void;
}

function ModeCard({ value, current, title, sub, onClick }: ModeCardProps) {
  const selected = value === current;
  return (
    <button
      type="button"
      onClick={() => onClick(value)}
      aria-pressed={selected}
      className="card"
      style={{
        textAlign: 'left',
        padding: 16,
        cursor: 'pointer',
        transition: 'border-color 0.15s',
        borderColor: selected ? 'var(--accent)' : undefined,
        boxShadow: selected ? '0 0 0 2px var(--accent-soft)' : undefined,
        background: 'var(--surface)',
        width: '100%',
      }}
    >
      <div style={{ fontWeight: 600, color: 'var(--text)', fontSize: 14 }}>{title}</div>
      <div style={{ fontSize: 12, color: 'var(--text-mute)', marginTop: 4 }}>{sub}</div>
    </button>
  );
}

export default function AaguidPolicyTab() {
  const { id } = useParams<{ id: string }>();
  const tenantId = id!;
  const [view, setView] = useState<AaguidPolicyView | null>(null);
  const [mode, setMode] = useState<AaguidPolicyMode>('ANY');
  const [mdsStrict, setMdsStrict] = useState(false);
  const [entries, setEntries] = useState<{ aaguid: string; note: string | null }[]>([]);
  const [newAaguid, setNewAaguid] = useState('');
  const [aaguidError, setAaguidError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  const dirty = useMemo(() => {
    if (!view) return false;
    if (mode !== view.mode) return true;
    if (mdsStrict !== view.mdsStrict) return true;
    if (entries.length !== view.entries.length) return true;
    const sorted = (a: string[]) => [...a].sort().join(',');
    return sorted(entries.map(e => e.aaguid)) !== sorted(view.entries.map(e => e.aaguid));
  }, [view, mode, mdsStrict, entries]);

  async function load() {
    const v = await aaguidPolicyApi.get(tenantId);
    setView(v);
    setMode(v.mode);
    setMdsStrict(v.mdsStrict);
    setEntries(v.entries.map(e => ({ aaguid: e.aaguid, note: e.note })));
  }

  useEffect(() => {
    load();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  function addChip() {
    const v = newAaguid.trim();
    if (!UUID_RE.test(v)) {
      setAaguidError('UUID 형식이 올바르지 않습니다');
      return;
    }
    if (entries.some(e => e.aaguid.toLowerCase() === v.toLowerCase())) {
      setAaguidError('이미 추가됨');
      return;
    }
    setEntries([...entries, { aaguid: v.toLowerCase(), note: null }]);
    setNewAaguid('');
    setAaguidError(null);
  }

  function removeChip(aaguid: string) {
    setEntries(entries.filter(e => e.aaguid !== aaguid));
  }

  async function save() {
    setSaving(true);
    try {
      await aaguidPolicyApi.update(tenantId, { mode, mdsStrict, entries });
      toast({ kind: 'ok', title: 'AAGUID 정책 저장 완료', message: '새 등록 ceremony 부터 적용됩니다' });
      await load();
    } finally {
      setSaving(false);
    }
  }

  if (!view) return <div className="muted" style={{ padding: 24, fontSize: 14 }}>Loading…</div>;

  return (
    <div className="stack-4" style={{ maxWidth: 960 }}>
      {/* Header */}
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, letterSpacing: '-0.011em' }}>AAGUID Policy</h2>
          <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--text-mute)' }}>
            등록 ceremony 의 인증기 허용/차단 정책. 인증 ceremony 에는 적용되지 않습니다.
          </p>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <button
            type="button"
            className="btn btn--sm"
            disabled={!dirty}
            onClick={() => load()}
          >
            취소
          </button>
          <button
            type="button"
            className="btn btn--primary btn--sm"
            disabled={!dirty || saving}
            onClick={save}
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
      </div>

      {/* Mode selection */}
      <div className="stack-2">
        <label className="label">모드</label>
        <div className="grid-3">
          <ModeCard
            value="ANY"
            current={mode}
            title="ANY"
            sub="모든 인증기 허용 (entry 무시)"
            onClick={setMode}
          />
          <ModeCard
            value="ALLOWLIST"
            current={mode}
            title="ALLOWLIST"
            sub="entry 에 있는 AAGUID 만 허용"
            onClick={setMode}
          />
          <ModeCard
            value="DENYLIST"
            current={mode}
            title="DENYLIST"
            sub="entry 에 있는 AAGUID 거부"
            onClick={setMode}
          />
        </div>
      </div>

      {/* MDS Strict toggle */}
      <div className="card">
        <div className="card__body row" style={{ justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
          <div>
            <div style={{ fontWeight: 500, color: 'var(--text)', fontSize: 14 }}>MDS Strict</div>
            <div style={{ fontSize: 12, color: 'var(--text-mute)', marginTop: 2 }}>
              FIDO MDS BLOB 에 등록되지 않은 인증기는 mode 와 무관하게 거부합니다.
            </div>
          </div>
          <Switch checked={mdsStrict} onChange={setMdsStrict} />
        </div>
      </div>

      {/* AAGUID entries (hidden in ANY mode) */}
      {mode !== 'ANY' && (
        <div className="card">
          <div className="card__head">
            <div className="card__title">AAGUID 목록</div>
            <div className="card__sub">{entries.length}개</div>
          </div>
          <div className="card__body stack-3">
            {/* Input row */}
            <div className="row" style={{ gap: 8 }}>
              <input
                className="input"
                style={{ flex: 1 }}
                value={newAaguid}
                placeholder="aaguid (UUID 형식, e.g. 00000000-0000-0000-0000-000000000000)"
                onChange={e => { setNewAaguid(e.target.value); setAaguidError(null); }}
                onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addChip(); } }}
              />
              <button
                type="button"
                className="btn btn--sm"
                onClick={addChip}
              >
                + 추가
              </button>
            </div>

            {aaguidError && (
              <div style={{ fontSize: 12, color: 'var(--danger)' }}>{aaguidError}</div>
            )}

            {/* Chip list */}
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {entries.length === 0 && (
                <div style={{ fontSize: 14, color: 'var(--text-mute)' }}>등록된 AAGUID 없음</div>
              )}
              {entries.map(e => (
                <span
                  key={e.aaguid}
                  className="badge"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
                >
                  <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                    {e.aaguid}
                  </span>
                  <button
                    type="button"
                    style={{
                      background: 'none',
                      border: 'none',
                      cursor: 'pointer',
                      color: 'var(--text-mute)',
                      padding: 0,
                      lineHeight: 1,
                      fontSize: 14,
                    }}
                    onClick={() => removeChip(e.aaguid)}
                    aria-label={`Remove ${e.aaguid}`}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
