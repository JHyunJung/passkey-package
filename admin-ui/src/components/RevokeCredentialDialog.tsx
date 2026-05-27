import { useState } from 'react';
import Dialog from './Dialog';
import Mono from './Mono';
import { revokeCredential } from '../api/client';
import { useToast } from './Toast';
import type { CredentialView } from '../api/types';

interface Props {
    open: boolean;
    credential: CredentialView | null;
    tenantId: string;
    onClose: () => void;
    onRevoked: () => void;
}

export default function RevokeCredentialDialog({
    open, credential, tenantId, onClose, onRevoked,
}: Props) {
    const [input, setInput] = useState('');
    const [busy, setBusy] = useState(false);
    const toast = useToast();

    if (!credential) return null;
    const last8 = credential.credentialId.slice(-8);
    const match = input === last8;

    async function confirm() {
        if (!credential || !match || busy) return;
        setBusy(true);
        try {
            await revokeCredential(tenantId, credential.credentialId);
            toast({ kind: 'ok', title: 'Credential 회수됨' });
            setInput('');
            onRevoked();
        } finally {
            setBusy(false);
        }
    }

    return (
        <Dialog
            open={open}
            onClose={onClose}
            title="Credential 회수"
            sub="이 작업은 되돌릴 수 없습니다."
            footer={
                <>
                    <button className="btn" onClick={onClose} disabled={busy}>취소</button>
                    <button className="btn btn--danger" onClick={confirm} disabled={!match || busy}>
                        {busy ? '회수 중…' : '회수'}
                    </button>
                </>
            }
        >
            <p style={{ color: 'var(--danger, #b00020)' }}>
                이 credential 을 회수하면 해당 사용자는 다시 등록(register)해야 로그인할 수 있습니다.
            </p>
            <p>
                확인을 위해 credential ID 의 마지막 8자를 입력하세요:{' '}
                <Mono short={last8} full={credential.credentialId} />
            </p>
            <input
                className="input"
                autoFocus
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="마지막 8자"
                style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
            />
        </Dialog>
    );
}
