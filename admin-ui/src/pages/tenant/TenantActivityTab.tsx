import { useCallback, useEffect, useState } from 'react';
import { getAuditLog } from '../../api/client';
import { formatDateTime } from '../../lib/formatDateTime';
import type { AuditLogView } from '../../api/types';

// Activity 페이지와 동일 5초 polling — 전역 일관성 유지. dogfood scale 에서 한
// tenant 의 audit 조회 부하는 무시할 수준 (단순 indexed query).
const POLL_MS = 5000;

interface Props {
    tenantId: string;
}

export default function TenantActivityTab({ tenantId }: Props) {
    const [rows, setRows] = useState<AuditLogView[]>([]);
    const [error, setError] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            const r = await getAuditLog({ tenantId, size: 100 });
            setRows(r);
            setError(null);
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [tenantId]);

    useEffect(() => { refresh(); }, [refresh]);
    useEffect(() => {
        const tick = setInterval(refresh, POLL_MS);
        return () => clearInterval(tick);
    }, [refresh]);

    if (error) return <div className="banner banner--danger">{error}</div>;

    return (
        <div className="stack-3">
            <div className="row" style={{ justifyContent: 'space-between' }}>
                <div className="muted">{rows.length} events</div>
                <button className="btn btn--ghost btn--sm" onClick={refresh}>새로고침</button>
            </div>
            <table className="table">
                <thead>
                    <tr><th>action</th><th>actor</th><th>target</th><th>at</th></tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={4} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                            audit 이벤트 없음
                        </td></tr>
                    )}
                    {rows.map(r => (
                        <tr key={r.id}>
                            <td><span className="badge">{r.action}</span></td>
                            <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{r.actorEmail}</td>
                            <td className="muted" style={{ fontSize: 12 }}>
                                {r.targetType ?? '—'}
                                {r.targetId && <> / {r.targetId.slice(0, 12)}…</>}
                            </td>
                            <td className="muted" style={{ fontSize: 12 }}>{formatDateTime(r.createdAt)}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
