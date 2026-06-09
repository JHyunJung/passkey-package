// localhost 와 127.0.0.1 은 webauthn 에서 별개로 취급된다. 항상 localhost 로 접근하라.
export function b64urlToBuf(s) {
  const pad = '='.repeat((4 - s.length % 4) % 4);
  const bin = atob((s + pad).replace(/-/g, '+').replace(/_/g, '/'));
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

export function bufToB64url(buf) {
  const bin = String.fromCharCode(...new Uint8Array(buf));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeCreationOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    user: { ...opts.user, id: b64urlToBuf(opts.user.id) },
    excludeCredentials: (opts.excludeCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
  };
}

export function encodeAttestationCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      attestationObject: bufToB64url(cred.response.attestationObject)
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}

export function decodeRequestOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    allowCredentials: (opts.allowCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
  };
}

export function encodeAssertionCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      authenticatorData: bufToB64url(cred.response.authenticatorData),
      signature:         bufToB64url(cred.response.signature),
      userHandle: cred.response.userHandle ? bufToB64url(cred.response.userHandle) : null
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}

export async function postJson(url, body) {
  const res  = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: 'omit'  // 무상태: 쿠키/세션 미참여를 명시
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(env.message || 'Unknown error');
    err.code = env.code; err.traceId = env.traceId; err.fieldErrors = env.error?.fieldErrors;
    throw err;
  }
  return env.data;
}
