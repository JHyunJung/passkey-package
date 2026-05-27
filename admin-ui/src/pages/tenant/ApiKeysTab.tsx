import { useEffect, useState, useCallback } from 'react';
import { api } from '../../api/client';
import { ApiError } from '../../api/types';
import type { ApiKeyView } from '../../api/types';
import { useToast } from '../../components/Toast';
import ApiKeyCreateModal from './ApiKeyCreateModal';
import { Plus, Key, Trash } from '../../components/Icons';
import { formatDateTime } from '../../lib/formatDateTime';

interface Props {
  tenantId: string;
}

export default function ApiKeysTab({ tenantId }: Props) {
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  const loadKeys = useCallback(
    (tid: string) => {
      setLoading(true);
      return api
        .get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tid)}`)
        .then((r) => { setKeys(r); setLoading(false); })
        .catch((e) => {
          setLoading(false);
          const err = e instanceof ApiError ? e : null;
          toast({
            kind: 'err',
            title: 'API 키 목록 실패',
            message: err?.serverMessage ?? String(e),
            traceId: err?.traceId,
          });
        });
    },
    [toast],
  );

  useEffect(() => {
    if (!tenantId) return;
    loadKeys(tenantId);
  }, [tenantId, loadKeys]);

  function refresh() {
    if (tenantId) loadKeys(tenantId);
  }

  async function revoke(id: string) {
    if (!confirm('이 API 키를 회수합니다. 즉시 사용 불가가 됩니다.')) return;
    try {
      await api.delete(`/admin/api/api-keys/${id}`);
      toast({ kind: 'ok', title: 'API 키 회수됨' });
      refresh();
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({
        kind: 'err',
        title: '회수 실패',
        message: e?.serverMessage ?? String(err),
        traceId: e?.traceId,
      });
    }
  }

  return (
    <div className="stack-6">
      <div className="card__head">
        <span className="muted" style={{ fontSize: 12 }}>{keys.length}건</span>
        <button className="btn btn--primary" onClick={() => setOpen(true)}>
          <Plus size={14} /> 키 발급
        </button>
      </div>

      {loading ? (
        <div className="card__body">
          <div className="skeleton" style={{ height: 100 }} />
        </div>
      ) : keys.length === 0 ? (
        <div className="empty">
          <div className="empty__art"><Key size={20} /></div>
          <div className="empty__title">발급된 API 키 없음</div>
          <div className="empty__sub">우측 상단 "키 발급" 버튼으로 시작하세요.</div>
        </div>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>이름</th>
              <th>PREFIX</th>
              <th>SCOPES</th>
              <th>STATUS</th>
              <th>CREATED</th>
              <th>EXPIRES</th>
              <th>LAST USED</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => {
              const isRevoked = !!k.revokedAt;
              return (
                <tr key={k.id}>
                  <td>{k.name}</td>
                  <td className="mono">{k.keyPrefix}…</td>
                  <td>
                    <div className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
                      {(k.scopes ?? []).map(s => (
                        <span key={s} className="badge">{s}</span>
                      ))}
                    </div>
                  </td>
                  <td>
                    <span className={`badge badge--${isRevoked ? 'danger' : 'success'} badge--dot`}>
                      {isRevoked ? 'REVOKED' : 'ACTIVE'}
                    </span>
                  </td>
                  <td className="mono muted">{formatDateTime(k.createdAt)}</td>
                  <td className="mono muted">{formatDateTime(k.expiresAt)}</td>
                  <td className="mono muted">{formatDateTime(k.lastUsedAt)}</td>
                  <td>
                    {!isRevoked && (
                      <button
                        className="btn btn--ghost btn--xs"
                        onClick={() => revoke(k.id)}
                        title="회수"
                      >
                        <Trash size={12} />
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {open && (
        <ApiKeyCreateModal
          tenantId={tenantId}
          onClose={() => setOpen(false)}
          onIssued={() => { setOpen(false); refresh(); }}
        />
      )}
    </div>
  );
}
