import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { NewTenantDialog } from './TenantsListPage';

afterEach(cleanup);

function renderDialog(onCreate = vi.fn()) {
  render(<NewTenantDialog open={true} onClose={vi.fn()} onCreate={onCreate} />);
  return {
    name: screen.getByPlaceholderText('예: Acme Corp') as HTMLInputElement,
    slug: screen.getByPlaceholderText('acme-corp') as HTMLInputElement,
    rpId: screen.getByPlaceholderText('예: passkey.acme.com') as HTMLInputElement,
    onCreate,
  };
}

describe('NewTenantDialog', () => {
  it('does not auto-fill slug/rpId from a mixed Korean+English display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: '크로스서트 Inc' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });

  it('does not auto-fill slug/rpId from an English display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: 'Acme Corp' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });

  it('accepts a slug starting with a digit (matches backend rule)', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: '123abc' } });
    fireEvent.change(rpId, { target: { value: 'passkey.acme.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).toHaveBeenCalledWith({ name: 'Acme', slug: '123abc', rpId: 'passkey.acme.com' });
  });

  it('submits manually entered name/slug/rpId verbatim', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: '크로스서트' } });
    fireEvent.change(slug, { target: { value: 'crosscert' } });
    fireEvent.change(rpId, { target: { value: 'passkey.crosscert.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).toHaveBeenCalledWith({ name: '크로스서트', slug: 'crosscert', rpId: 'passkey.crosscert.com' });
  });

  it('does not call onCreate when slug is invalid', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: 'A' } }); // 대문자 1글자 — invalid (too short + uppercase stripped by onChange to 'a' but length 1 < 2)
    fireEvent.change(rpId, { target: { value: 'passkey.acme.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).not.toHaveBeenCalled();
  });
});
