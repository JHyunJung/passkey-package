import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
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

  it('does not call onCreate when slug has an invalid character', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: 'a_b' } }); // 언더스코어는 slug 정규식 불허 — lowercase 후에도 invalid
    fireEvent.change(rpId, { target: { value: 'passkey.acme.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).not.toHaveBeenCalled();
  });
  it('V2: 다음 단계 3개 + RP ID 잠금 안내', () => {
    renderDialog();
    const steps = screen.getByRole('list', { name: '다음 단계' });
    expect(within(steps).getAllByRole('listitem')).toHaveLength(3);
    expect(within(steps).getByText('WebAuthn config')).toBeInTheDocument();
    expect(within(steps).getByText('AAGUID 정책')).toBeInTheDocument();
    expect(within(steps).getByText('API key 발급')).toBeInTheDocument();
    expect(screen.getByText('생성 후에는 변경할 수 없습니다. 기존 credential 이 이 값에 묶입니다.')).toBeInTheDocument();
    expect(screen.getByLabelText('RP ID')).toHaveAttribute('placeholder', '예: passkey.acme.com');
  });

  it('V2: placeholder 도메인 rpId 는 제출 시 오류를 보이고 생성하지 않는다', () => {
    const onCreate = vi.fn();
    const { name, slug, rpId } = renderDialog(onCreate);
    fireEvent.change(name, { target: { value: 'Acme' } });
    fireEvent.change(slug, { target: { value: 'acme' } });
    fireEvent.change(rpId, { target: { value: 'passkey.example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '생성하고 설정으로 이동' }));
    expect(onCreate).not.toHaveBeenCalled();
    expect(screen.getByText(/example.com은 placeholder라 사용할 수 없습니다/)).toBeInTheDocument();
  });
});
