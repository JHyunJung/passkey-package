import { useCallback, useEffect, useState } from 'react';
import { listCredentials } from '../../api/client';
import Pagination from '../../components/Pagination';
import Mono from '../../components/Mono';
import RevokeCredentialDialog from '../../components/RevokeCredentialDialog';
import { formatDateTime } from '../../lib/formatDateTime';
import type { CredentialView, PageView } from '../../api/types';

interface Props {
    tenantId: string;
}

const PAGE_SIZE = 50;

export default function CredentialsTab({ tenantId }: Props) {
    const [page, setPage] = useState(0);
    const [q, setQ] = useState('');
    const [data, setData] = useState<PageView<CredentialView> | null>(null);
    const [target, setTarget] = useState<CredentialView | null>(null);
    const [busy, setBusy] = useState(false);

    const refresh = useCallback(() => {
        setBusy(true);
        listCredentials(tenantId, { page, size: PAGE_SIZE, q: q || undefined })
            .then(setData)
            .finally(() => setBusy(false));
    }, [tenantId, page, q]);

    useEffect(() => { refresh(); }, [refresh]);

    return (
        <div className="stack-3">
            <input
                className="input"
                placeholder="credentialId 또는 userHandle 일부…"
                value={q}
                onChange={(e) => { setPage(0); setQ(e.target.value); }}
                style={{ maxWidth: 360 }}
            />

            <table className="table">
                <thead>
                    <tr>
                        <th>credentialId</th>
                        <th>userHandle</th>
                        <th>Authenticator</th>
                        <th>fmt</th>
                        <th style={{ textAlign: 'right' }}>signCount</th>
                        <th>last used</th>
                        <th>created</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {data?.content.length === 0 && (
                        <tr>
                            <td colSpan={8} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                                credential 없음
                            </td>
                        </tr>
                    )}
                    {data?.content.map((c) => (
                        <tr key={c.credentialId}>
                            <td><Mono short={c.credentialId.slice(-8)} full={c.credentialId} /></td>
                            <td><Mono short={c.userHandle.slice(0, 12)}  full={c.userHandle} /></td>
                            <td>
                                {c.authenticatorName ?? (
                                    <span className="muted">
                                        aaguid {c.aaguidHex ? c.aaguidHex.slice(0, 8) + '…' : '—'}
                                    </span>
                                )}
                            </td>
                            <td>{c.attestationFormat}</td>
                            <td style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                                {c.signCount}
                            </td>
                            <td>{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : '—'}</td>
                            <td>{formatDateTime(c.createdAt)}</td>
                            <td>
                                <button className="btn btn--danger btn--sm"
                                        onClick={() => setTarget(c)}>
                                    회수
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            <Pagination
                page={page}
                size={PAGE_SIZE}
                total={data?.totalElements ?? 0}
                onChange={setPage}
            />

            <RevokeCredentialDialog
                open={target !== null}
                credential={target}
                tenantId={tenantId}
                onClose={() => setTarget(null)}
                onRevoked={() => { setTarget(null); refresh(); }}
            />

            {busy && <div className="muted" style={{ fontSize: 12 }}>불러오는 중…</div>}
        </div>
    );
}
