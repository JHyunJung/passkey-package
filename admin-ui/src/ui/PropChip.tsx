import { CopyBtn } from './CopyBtn';

export function PropChip({ label, value, copy }: { label: string; value: string; copy?: boolean }) {
  return (
    <span className={copy ? 'propchip' : 'propchip propchip--nocopy'}>
      <span className="propchip__k">{label}</span>
      <span className="propchip__v" title={value}>{value}</span>
      {copy && <CopyBtn value={value} label={`${label} 복사`} iconOnly />}
    </span>
  );
}
