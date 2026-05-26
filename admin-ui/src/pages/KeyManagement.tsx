import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { KeyList, RotateResponse, SigningKeyView } from '../api/types';

export default function KeyManagement() {
  const [keys, setKeys] = useState<SigningKeyView[]>([]);
  const [rotating, setRotating] = useState(false);
  const [lastResult, setLastResult] = useState<RotateResponse | string | null>(null);

  function refresh() {
    api.get<KeyList>('/admin/api/keys').then(r => setKeys(r.keys));
  }

  useEffect(refresh, []);

  async function rotate() {
    if (!confirm('Rotate signing key now? Existing JWTs remain valid for ~30 minutes after.')) return;
    setRotating(true);
    try {
      const r = await api.post<RotateResponse>('/admin/api/keys/rotate', {});
      setLastResult(r);
      refresh();
    } catch (e) {
      setLastResult(String(e));
    } finally {
      setRotating(false);
    }
  }

  return (
    <div>
      <h2>Signing Keys</h2>
      <p>
        <button onClick={rotate} disabled={rotating}>
          {rotating ? 'Rotating…' : 'Rotate now'}
        </button>
      </p>
      {lastResult && (
        <p>
          {typeof lastResult === 'string'
            ? <code>{lastResult}</code>
            : <span>Rotated <code>{lastResult.oldKid}</code> → <code>{lastResult.newKid}</code></span>}
        </p>
      )}
      <table border={1} cellPadding={4} cellSpacing={0}>
        <thead>
          <tr><th>kid</th><th>alg</th><th>status</th><th>created</th><th>rotated</th><th>revoked</th></tr>
        </thead>
        <tbody>
          {keys.map(k => (
            <tr key={k.id}>
              <td><code>{k.kid}</code></td>
              <td>{k.alg}</td>
              <td>{k.status}</td>
              <td>{k.createdAt}</td>
              <td>{k.rotatedAt ?? '-'}</td>
              <td>{k.revokedAt ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
