export function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    ACTIVE: 'success',
    REVOKED: 'danger',
    EXPIRED: 'warning',
    SUSPENDED: 'warning',
    PENDING: 'info',
  };
  return <span className={`badge badge--${map[status] || 'default'} badge--dot`}>{status}</span>;
}
