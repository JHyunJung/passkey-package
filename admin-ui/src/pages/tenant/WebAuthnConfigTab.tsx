import { useState } from 'react';
import { updateTenant } from '../../api/client';
import { useToast } from '../../components/Toast';
import OriginChipInput from '../../components/OriginChipInput';
import FormatCheckboxGrid from '../../components/FormatCheckboxGrid';
import Switch from '../../components/Switch';
import type { TenantView, TenantUpdateRequest } from '../../api/types';

interface Props {
    tenant: TenantView;
    onUpdated: (t: TenantView) => void;
}

// Internal draft uses Set<string> for acceptedFormats to match FormatCheckboxGrid's API.
interface Draft {
    displayName: string;
    rpName: string;
    allowedOrigins: string[];
    acceptedFormats: Set<string>;
    requireUserVerification: boolean;
    mdsRequired: boolean;
}

function toDraft(t: TenantView): Draft {
    return {
        displayName: t.displayName,
        rpName: t.rpName,
        allowedOrigins: [...t.allowedOrigins],
        acceptedFormats: new Set(t.acceptedFormats),
        requireUserVerification: t.requireUserVerification,
        mdsRequired: t.mdsRequired,
    };
}

function toRequest(d: Draft): TenantUpdateRequest {
    return {
        displayName: d.displayName,
        rpName: d.rpName,
        allowedOrigins: [...d.allowedOrigins],
        acceptedFormats: [...d.acceptedFormats],
        requireUserVerification: d.requireUserVerification,
        mdsRequired: d.mdsRequired,
    };
}

function shallowEqual(a: Draft, b: Draft): boolean {
    return (
        a.displayName === b.displayName &&
        a.rpName === b.rpName &&
        a.requireUserVerification === b.requireUserVerification &&
        a.mdsRequired === b.mdsRequired &&
        a.allowedOrigins.length === b.allowedOrigins.length &&
        a.allowedOrigins.every((v, i) => v === b.allowedOrigins[i]) &&
        a.acceptedFormats.size === b.acceptedFormats.size &&
        [...a.acceptedFormats].every((v) => b.acceptedFormats.has(v))
    );
}

export default function WebAuthnConfigTab({ tenant, onUpdated }: Props) {
    const [draft, setDraft] = useState<Draft>(toDraft(tenant));
    const [busy, setBusy] = useState(false);
    const toast = useToast();
    const dirty = !shallowEqual(draft, toDraft(tenant));

    async function save() {
        setBusy(true);
        try {
            const updated = await updateTenant(tenant.id, toRequest(draft));
            onUpdated(updated);
            toast({ kind: 'ok', title: 'WebAuthn 설정 저장됨' });
        } finally {
            setBusy(false);
        }
    }

    return (
        <form
            className="stack-4"
            onSubmit={(e) => {
                e.preventDefault();
                if (dirty && !busy) save();
            }}
        >
            <ReadOnlyField label="rpId" value={tenant.rpId}
                           note="rpId 변경은 credential 영향 분석 후 별도 워크플로우" />
            <ReadOnlyField label="slug" value={tenant.slug} />

            <Field label="displayName">
                <input
                    className="input"
                    value={draft.displayName}
                    onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
                />
            </Field>
            <Field label="rpName">
                <input
                    className="input"
                    value={draft.rpName}
                    onChange={(e) => setDraft({ ...draft, rpName: e.target.value })}
                />
            </Field>

            <Field label="Allowed Origins">
                <OriginChipInput
                    value={draft.allowedOrigins}
                    onChange={(v) => setDraft({ ...draft, allowedOrigins: v })}
                />
            </Field>

            <Field label="Accepted Formats">
                <FormatCheckboxGrid
                    value={draft.acceptedFormats}
                    onChange={(v) => setDraft({ ...draft, acceptedFormats: v })}
                />
            </Field>

            <Field label="requireUserVerification">
                <Switch
                    checked={draft.requireUserVerification}
                    onChange={(v) => setDraft({ ...draft, requireUserVerification: v })}
                />
            </Field>

            <Field label="mdsRequired">
                <Switch
                    checked={draft.mdsRequired}
                    onChange={(v) => setDraft({ ...draft, mdsRequired: v })}
                />
            </Field>

            <div className="row" style={{ gap: 8 }}>
                <button type="submit" className="btn btn--primary" disabled={!dirty || busy}>
                    {busy ? '저장 중…' : '저장'}
                </button>
                <button type="button" className="btn" disabled={!dirty || busy}
                        onClick={() => setDraft(toDraft(tenant))}>되돌리기</button>
            </div>
        </form>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="stack-2">
            <label className="label">{label}</label>
            {children}
        </div>
    );
}

function ReadOnlyField({ label, value, note }: { label: string; value: string; note?: string }) {
    return (
        <div className="stack-2">
            <label className="label">{label}</label>
            <div className="input" style={{
                background: 'var(--surface-2, #f4f5f7)',
                color: 'var(--text-mute, #666)',
                cursor: 'not-allowed',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            }}>{value}</div>
            {note && <div className="muted" style={{ fontSize: 12 }}>{note}</div>}
        </div>
    );
}
