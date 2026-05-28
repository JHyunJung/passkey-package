import { EmptyState } from '@/shell/EmptyState';

export default function FunnelTab() {
  return (
    <div style={{ padding: 24 }}>
      <EmptyState
        icon="Activity"
        title="Funnel 시각화 — 준비 중"
        description="등록/인증 funnel, 일별/타입별 차트는 Phase E3 에서 구현됩니다."
      />
    </div>
  );
}
