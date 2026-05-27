import { useEffect, useState } from 'react';
import { api, listCredentials } from '../../api/client';
import type { TenantView, ApiKeyView } from '../../api/types';

interface Props {
    tenant: TenantView;
}

export default function OverviewTab({ tenant }: Props) {
    const [credCount, setCredCount] = useState<number | null>(null);
    const [keyCount, setKeyCount] = useState<number | null>(null);

    useEffect(() => {
        listCredentials(tenant.id, { page: 0, size: 1 })
            .then((p) => setCredCount(p.totalElements))
            .catch(() => setCredCount(null));
        api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenant.id}`)
            .then((rows) => setKeyCount(rows.length))
            .catch(() => setKeyCount(null));
    }, [tenant.id]);

    return (
        <div className="stack-4">
            <div className="row" style={{ gap: 16 }}>
                <Kpi label="Credentials" value={credCount} />
                <Kpi label="API Keys"    value={keyCount} />
            </div>

            <section className="card stack-3" style={{ padding: 16 }}>
                <h3 style={{ marginTop: 0 }}>WebAuthn 설정</h3>
                <Row label="rpId"   value={tenant.rpId} mono />
                <Row label="rpName" value={tenant.rpName} />
                <Row label="Allowed Origins">
                    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {tenant.allowedOrigins.map((o) => (
                            <span key={o} className="chip">{o}</span>
                        ))}
                    </div>
                </Row>
                <Row label="Accepted Formats">
                    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {tenant.acceptedFormats.map((f) => (
                            <span key={f} className="chip">{f}</span>
                        ))}
                    </div>
                </Row>
                <Row label="requireUserVerification" value={tenant.requireUserVerification ? 'Y' : 'N'} />
                <Row label="mdsRequired"             value={tenant.mdsRequired ? 'Y' : 'N'} />
            </section>
        </div>
    );
}

function Kpi({ label, value }: { label: string; value: number | null }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="muted" style={{ fontSize: 12 }}>{label}</div>
            <div style={{ fontSize: 28, fontWeight: 600 }}>{value === null ? '—' : value}</div>
        </div>
    );
}

function Row({
    label, value, mono, children,
}: {
    label: string;
    value?: string;
    mono?: boolean;
    children?: React.ReactNode;
}) {
    return (
        <div className="row" style={{ gap: 16, alignItems: 'baseline' }}>
            <div style={{ width: 200, color: 'var(--text-mute)' }}>{label}</div>
            {children ?? (
                <div style={{
                    fontFamily: mono ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined,
                }}>
                    {value}
                </div>
            )}
        </div>
    );
}
