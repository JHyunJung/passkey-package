import { useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { TenantView } from '../api/types';
import { useToast } from '../components/Toast';

const DEFAULT_POLICY = JSON.stringify(
  { acceptedFormats: ['none', 'packed'], requireUserVerification: true, mdsRequired: false },
  null, 2
);

export default function TenantCreate() {
  const nav = useNavigate();
  const toast = useToast();
  const [id, setId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [rpId, setRpId] = useState('');
  const [rpName, setRpName] = useState('');
  const [allowedOriginsJson, setAllowedOriginsJson] = useState('["http://localhost"]');
  const [attestationPolicyJson, setAttestationPolicyJson] = useState(DEFAULT_POLICY);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post<TenantView>('/admin/api/tenants', {
        id, displayName, rpId, rpName, allowedOriginsJson, attestationPolicyJson,
      });
      toast({ kind: 'ok', title: 'Tenant 생성됨', message: id });
      nav('/tenants');
    } catch (err) {
      if (err instanceof ApiError) {
        toast({ kind: 'err', title: '생성 실패', message: err.serverMessage, traceId: err.traceId });
      } else {
        toast({ kind: 'err', title: '생성 실패', message: '알 수 없는 오류가 발생했습니다' });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack-6" style={{ maxWidth: 720 }}>
      <div className="page__head">
        <div>
          <h1 className="page__title">신규 Tenant</h1>
          <div className="page__sub">RP 회사 단위의 격리 환경을 생성합니다.</div>
        </div>
      </div>
      <form onSubmit={submit} className="card">
        <div className="card__body stack-4">
          <Field label="Tenant ID" hint="고유 식별자. 영문 소문자 + 숫자 + 하이픈.">
            <input
              className="input mono"
              value={id}
              onChange={(e) => setId(e.target.value)}
              required
              pattern="^[a-z0-9][a-z0-9-]{1,62}$"
            />
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
          <Field label="허용 origin (JSON 배열)">
            <textarea
              className="input mono"
              value={allowedOriginsJson}
              onChange={(e) => setAllowedOriginsJson(e.target.value)}
              rows={2}
              required
            />
          </Field>
          <Field label="Attestation 정책 (JSON)">
            <textarea
              className="input mono"
              value={attestationPolicyJson}
              onChange={(e) => setAttestationPolicyJson(e.target.value)}
              rows={4}
              required
            />
          </Field>
        </div>
        <div className="dialog__foot" style={{ borderRadius: '0 0 var(--radius-lg) var(--radius-lg)' }}>
          <button type="button" className="btn btn--outline" onClick={() => nav('/tenants')}>취소</button>
          <button type="submit" className="btn btn--primary" disabled={busy}>{busy ? '생성 중…' : '생성'}</button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
