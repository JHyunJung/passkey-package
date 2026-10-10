import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('main.tsx router', () => {
  it('createBrowserRouter + RouterProvider 를 쓰고 BrowserRouter 를 쓰지 않는다', () => {
    const src = readFileSync(join(__dirname, '..', 'main.tsx'), 'utf8');
    expect(src).toMatch(/createBrowserRouter/);
    expect(src).toMatch(/RouterProvider/);
    expect(src).not.toMatch(/<BrowserRouter/);
  });
});
