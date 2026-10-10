import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const SRC = join(__dirname, '..');
const read = (f: string) => readFileSync(join(SRC, f), 'utf8');

// App.tsx <Routes> 가 렌더하는 화면(라우트 element 의 루트 컴포넌트 파일)
const ROUTED = [
  'pages/TenantsListPage.tsx',
  'pages/TenantDetailPage.tsx',
  'pages/ActivityPage.tsx',
  'pages/AuditChainPage.tsx',
  'pages/SettingsPage.tsx',
  'pages/LicensePage.tsx',
  'me/RequirePlatform.tsx',
];
// 한 줄 return 으로 div/section/main/p 를 돌려주는데 className="page" 가 없는 경우(로딩·오류 조기 return)
const RETURN_WITHOUT_PAGE = /^\s*(if \(.*\)\s*)?return\s*<(div|section|main|p)\b(?![^>]*className="page")/m;
const NESTED_DIRS = ['pages/settings', 'pages/tenant'];

describe('routed page roots', () => {
  it('App.tsx 에 Task 12 의 임시 .page 래퍼가 없다', () => {
    expect(read('App.tsx')).not.toMatch(/<div className="page">\s*<Routes>/);
  });
  it.each(ROUTED)('%s 는 className="page" 루트를 가진다', (f) => {
    expect(read(f)).toMatch(/className="page"/);
  });
  it.each(ROUTED)('%s 의 한 줄(조기) return 도 .page 를 쓴다', (f) => {
    expect(read(f)).not.toMatch(RETURN_WITHOUT_PAGE);
  });
  it.each(NESTED_DIRS)('%s 의 탭 컴포넌트는 .page 를 중첩하지 않는다', (dir) => {
    const offenders = readdirSync(join(SRC, dir))
      .filter((n) => n.endsWith('.tsx') && !n.endsWith('.test.tsx'))
      .filter((n) => /className="page"/.test(read(`${dir}/${n}`)));
    expect(offenders).toEqual([]);
  });
});
