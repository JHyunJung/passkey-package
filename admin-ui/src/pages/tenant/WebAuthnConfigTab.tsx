import { useState } from 'react';
import { updateTenant } from '../../api/client';
import { webauthnDiffApi } from '../../api/aaguidPolicy';
import { useToast } from '../../components/Toast';
import OriginChipInput from '../../components/OriginChipInput';
import FormatCheckboxGrid from '../../components/FormatCheckboxGrid';
import Switch from '../../components/Switch';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
  DialogFooter,
  DialogClose,
} from '../../components/ui/dialog';
import type { TenantView, TenantUpdateRequest, WebauthnConfigDiff } from '../../api/types';

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
    const [diffOpen, setDiffOpen] = useState(false);
    const [diffResult, setDiffResult] = useState<WebauthnConfigDiff | null>(null);
    const [diffLoading, setDiffLoading] = useState(false);
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

    async function previewDiff() {
        setDiffLoading(true);
        try {
            const req = toRequest(draft);
            const result = await webauthnDiffApi.diff(tenant.id, {
                rpId: tenant.rpId,
                rpName: req.rpName,
                allowedOrigins: req.allowedOrigins,
                acceptedFormats: req.acceptedFormats,
                requireUserVerification: req.requireUserVerification,
                mdsRequired: req.mdsRequired,
            });
            setDiffResult(result);
            setDiffOpen(true);
        } catch {
            toast({ kind: 'err', title: 'diff 조회 실패' });
        } finally {
            setDiffLoading(false);
        }
    }

    return (
        <>
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
                <button
                    type="button"
                    className="btn btn--outline btn--sm"
                    disabled={!dirty || diffLoading}
                    onClick={previewDiff}
                    style={{ marginLeft: 8 }}
                >
                    {diffLoading ? '분석 중…' : '변경 미리보기'}
                </button>
            </div>
        </form>

        <Dialog open={diffOpen} onOpenChange={setDiffOpen}>
            <DialogContent wide>
                <DialogHeader>
                    <DialogTitle>WebAuthn 설정 변경 미리보기</DialogTitle>
                    <DialogDescription>
                        {diffResult ? `${diffResult.changes.length}개 필드 변경됨` : ''}
                    </DialogDescription>
                </DialogHeader>
                <DialogBody>
                    {diffResult && (
                        <div className="stack-3">
                            {diffResult.warnings.length > 0 && (
                                <div className="banner banner--danger">
                                    <div className="stack-1">
                                        {diffResult.warnings.map((w, i) => (
                                            <div key={i} style={{ fontSize: 13 }}>{w}</div>
                                        ))}
                                    </div>
                                </div>
                            )}
                            {diffResult.changes.length === 0 && (
                                <div style={{ color: 'var(--text-mute)', fontSize: 14 }}>변경 사항 없음</div>
                            )}
                            {diffResult.changes.map((c, i) => (
                                <div key={i} className="card">
                                    <div className="card__body stack-2">
                                        <div style={{ fontWeight: 600, fontSize: 13, color: 'var(--text)' }}>{c.field}</div>
                                        {c.added && c.added.length > 0 && (
                                            <div className="stack-1">
                                                {c.added.map((item, j) => (
                                                    <div key={j} style={{ fontSize: 12, color: 'var(--success)', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                                                        + {item}
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                        {c.removed && c.removed.length > 0 && (
                                            <div className="stack-1">
                                                {c.removed.map((item, j) => (
                                                    <div key={j} style={{ fontSize: 12, color: 'var(--danger)', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                                                        - {item}
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                        {!c.added && !c.removed && (
                                            <div style={{ fontSize: 12, color: 'var(--text-soft)' }}>
                                                <span style={{ color: 'var(--danger)' }}>{String(c.from)}</span>
                                                <span style={{ margin: '0 6px', color: 'var(--text-mute)' }}>→</span>
                                                <span style={{ color: 'var(--success)' }}>{String(c.to)}</span>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </DialogBody>
                <DialogFooter>
                    <DialogClose asChild>
                        <button type="button" className="btn btn--sm">닫기</button>
                    </DialogClose>
                </DialogFooter>
            </DialogContent>
        </Dialog>
        </>
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
