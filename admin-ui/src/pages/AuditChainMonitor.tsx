import { useEffect, useState } from 'react';
import { auditChainApi } from '@/api/auditChain';
import type { AuditChainOverview } from '@/api/types';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/components/Toast';

export default function AuditChainMonitor() {
  const [data, setData] = useState<AuditChainOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  async function load() {
    setLoading(true);
    try {
      const d = await auditChainApi.overview(24);
      setData(d);
    } catch {
      /* toast bridge */
    } finally {
      setLoading(false);
    }
  }

  async function runBackfill() {
    try {
      const r = await auditChainApi.backfill();
      toast({
        kind: 'ok',
        title: '백필 완료',
        message: `${r.tenantsProcessed} tenants · ${r.rowsUpdated} updated · ${r.rowsSkipped} skipped`,
      });
      await load();
    } catch {
      /* toast bridge */
    }
  }

  useEffect(() => {
    load();
  }, []);

  if (!data) {
    return (
      <div className="p-8">
        <div className="text-text-mute text-sm">{loading ? 'Loading…' : 'No data'}</div>
      </div>
    );
  }

  const t = data.totals;
  const tamperedTenants = data.tenants.filter((x) => !x.intact);

  return (
    <div className="p-8 max-w-[1440px] mx-auto">
      <div className="flex items-end justify-between mb-5">
        <div>
          <h1 className="text-[22px] font-semibold tracking-[-0.011em]">Audit Chain Monitor</h1>
          <p className="text-[13px] text-text-mute mt-1">
            Last verified {new Date(data.verifiedAt).toLocaleString()} · {t.verificationMs}ms
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="default" onClick={load} disabled={loading}>
            {loading ? 'Verifying…' : 'Re-verify'}
          </Button>
          <Button variant="outline" onClick={runBackfill}>
            Run backfill
          </Button>
        </div>
      </div>

      {tamperedTenants.length > 0 && (
        <div className="banner banner--danger mb-5">
          <div className="banner__icon">⚠</div>
          <div>
            <div className="banner__title">
              {tamperedTenants.length}개 tenant 에서 위변조 의심
            </div>
            <div className="banner__body">
              {tamperedTenants.map((x) => x.tenantName).join(', ')}
            </div>
          </div>
        </div>
      )}

      <div className="grid-4 mb-6">
        <KpiCard label="Intact / total" value={`${t.tenantsIntact} / ${t.tenantsTotal}`} />
        <KpiCard label="Tampered" value={t.tenantsTampered} />
        <KpiCard label="Verified rows" value={t.verifiedRows.toLocaleString()} />
        <KpiCard label="Verify time" value={`${t.verificationMs}ms`} />
      </div>

      <div className="grid-3">
        {data.tenants.map((x) => (
          <div key={x.tenantId ?? 'platform'} className="card">
            <div className="card__head">
              <div>
                <div className="card__title">{x.tenantName}</div>
                <div className="card__sub">{x.verifiedRows} rows</div>
              </div>
              <Badge variant={x.intact ? 'success' : 'danger'} dot>
                {x.intact ? 'intact' : 'tampered'}
              </Badge>
            </div>
            <div className="card__body">
              <Sparkline buckets={x.buckets} />
              {!x.intact && x.tamperedEntryId && (
                <div className="text-[11px] text-danger mt-2 font-mono">
                  broken at id {x.tamperedEntryId}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function KpiCard({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="card">
      <div className="card__body">
        <div className="metric-label">{label}</div>
        <div className="metric-value">{value}</div>
      </div>
    </div>
  );
}

function Sparkline({ buckets }: { buckets: number[] }) {
  const max = Math.max(...buckets, 1);
  const width = buckets.length * 4;
  return (
    <svg viewBox={`0 0 ${width} 24`} className="w-full h-6" preserveAspectRatio="none">
      {buckets.map((b, i) => {
        const h = Math.max(1, (b / max) * 24);
        return (
          <rect
            key={i}
            x={i * 4}
            y={24 - h}
            width={3}
            height={h}
            className="fill-accent"
          />
        );
      })}
    </svg>
  );
}
