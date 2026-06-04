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
  it('does not auto-fill slug/rpId from a Korean display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: '한글회사' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });

  it('does not auto-fill slug/rpId from an English display name', () => {
    const { name, slug, rpId } = renderDialog();
    fireEvent.change(name, { target: { value: 'Acme Corp' } });
    expect(slug.value).toBe('');
    expect(rpId.value).toBe('');
  });
});
