import * as React from 'react';
import { Switch as UiSwitch } from '@/components/ui/switch';

interface Props {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: React.ReactNode;
  disabled?: boolean;
}

/**
 * @deprecated 기존 페이지 호환용 shim. 신규 코드는 `@/components/ui/switch` 직접 사용.
 */
export default function Switch({ checked, onChange, label, disabled }: Props) {
  return (
    <label
      className="row"
      style={{ gap: 10, cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.5 : 1 }}
    >
      <UiSwitch checked={checked} onCheckedChange={onChange} disabled={disabled} />
      {label && <span style={{ fontSize: 13 }}>{label}</span>}
    </label>
  );
}
