import { tenantsApi } from '@/api/tenants';

export type SearchResult = {
  type: 'tenant' | 'credential' | 'audit';
  id: string;
  label: string;
  sub?: string;
  onSelect: () => void;
};

let tenantCache: { name: string; slug: string; id: string }[] | null = null;
let cachedAt = 0;
const CACHE_MS = 30_000;

export async function searchAll(query: string, navigate: (path: string) => void): Promise<SearchResult[]> {
  if (!query.trim()) return [];
  const q = query.toLowerCase();
  const results: SearchResult[] = [];

  // tenant 검색 (실 API, 30s 캐시)
  try {
    if (!tenantCache || Date.now() - cachedAt > CACHE_MS) {
      const list = await tenantsApi.list();
      tenantCache = list.map((t) => ({ name: t.name, slug: t.slug, id: t.id }));
      cachedAt = Date.now();
    }
    tenantCache
      .filter((t) => t.name.toLowerCase().includes(q) || t.slug.toLowerCase().includes(q) || t.id.toLowerCase().includes(q))
      .slice(0, 8)
      .forEach((t) => {
        results.push({
          type: 'tenant',
          id: t.id,
          label: t.name,
          sub: t.slug,
          onSelect: () => navigate(`/tenants/${t.id}?tab=overview`),
        });
      });
  } catch {
    /* ignore */
  }

  // credential / audit ID 검색 — 서버 search endpoint 없음. Phase E4 에서 추가.
  return results;
}

export function invalidateTenantCache() {
  tenantCache = null;
  cachedAt = 0;
}
