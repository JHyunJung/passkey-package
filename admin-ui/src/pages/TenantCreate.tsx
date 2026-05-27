import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { TenantView, TenantCreateRequest } from '../api/types';
import { useToast } from '../components/Toast';
import OriginChipInput from '../components/OriginChipInput';
import FormatCheckboxGrid from '../components/FormatCheckboxGrid';
import Switch from '../components/Switch';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';

const DEFAULT_FORMATS = new Set([
  'none', 'packed', 'android-key', 'android-safetynet', 'fido-u2f', 'apple', 'tpm'
]);

export default function TenantCreate() {
  const nav = useNavigate();
  const toast = useToast();

  // Identity fields
  const [slug, setSlug] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [rpId, setRpId] = useState('');
  const [rpName, setRpName] = useState('');

  // Origins + policy
  const [allowedOrigins, setAllowedOrigins] = useState<string[]>([]);
  const [acceptedFormats, setAcceptedFormats] = useState<Set<string>>(DEFAULT_FORMATS);
  const [requireUV, setRequireUV] = useState(true);
  const [mdsRequired, setMdsRequired] = useState(false);

  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (allowedOrigins.length === 0) {
      toast({ kind: 'err', title: '허용 origin을 최소 1개 입력하세요' });
      return;
    }
    if (acceptedFormats.size === 0) {
      toast({ kind: 'err', title: 'attestation format을 최소 1개 선택하세요' });
      return;
    }
    setBusy(true);
    try {
      const body: TenantCreateRequest = {
        slug, displayName, rpId, rpName,
        allowedOrigins,
        acceptedFormats: [...acceptedFormats],
        requireUserVerification: requireUV,
        mdsRequired,
      };
      await api.post<TenantView>('/admin/api/tenants', body);
      toast({ kind: 'ok', title: 'Tenant 생성됨', message: slug });
      nav('/tenants');
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '생성 실패', message: e?.serverMessage ?? String(err), traceId: e?.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PlatformOnlyGuard />
      <div className="stack-6" style={{ maxWidth: 720 }}>
        <div className="page__head">
          <div>
            <h1 className="page__title">신규 Tenant</h1>
            <div className="page__sub">RP 회사 단위의 격리 환경을 생성합니다.</div>
          </div>
        </div>
        <form onSubmit={submit} className="card">
          <div className="card__body stack-4">
            <Field label="Tenant slug" hint="고유 식별자. 영문 소문자 + 숫자 + 하이픈. URL에 사용.">
              <input className="input mono" value={slug} onChange={(e) => setSlug(e.target.value)}
                required pattern="^[a-z0-9][a-z0-9-]{1,62}$" />
            </Field>
            <Field label="표시 이름">
              <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </Field>
            <div className="grid-2">
              <Field label="RP ID" hint="WebAuthn rpId. e.g. acme.example.com">
                <input className="input mono" value={rpId} onChange={(e) => setRpId(e.target.value)} required />
              </Field>
              <Field label="RP 이름">
                <input className="input" value={rpName} onChange={(e) => setRpName(e.target.value)} required />
              </Field>
            </div>
            <Field label="허용 origin" hint="WebAuthn ceremony에서 허용할 origin URL (http(s)://...). Enter로 추가.">
              <OriginChipInput value={allowedOrigins} onChange={setAllowedOrigins} />
            </Field>
            <Field label="허용 attestation format" hint="기본 7개 모두 허용. 보안 강화 시 일부만 체크.">
              <FormatCheckboxGrid value={acceptedFormats} onChange={setAcceptedFormats} />
            </Field>
            <Field label="정책">
              <div className="stack-2">
                <Switch checked={requireUV} onChange={setRequireUV} label="User Verification 필수 (passkey 권장)" />
                <Switch checked={mdsRequired} onChange={setMdsRequired} label="FIDO MDS 검증 필수 (고보안)" />
              </div>
            </Field>
          </div>
          <div className="dialog__foot" style={{ borderRadius: '0 0 var(--radius-lg) var(--radius-lg)' }}>
            <button type="button" className="btn btn--outline" onClick={() => nav('/tenants')}>취소</button>
            <button type="submit" className="btn btn--primary" disabled={busy}>{busy ? '생성 중…' : '생성'}</button>
          </div>
        </form>
      </div>
    </>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
