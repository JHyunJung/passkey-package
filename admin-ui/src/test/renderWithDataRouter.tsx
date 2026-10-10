import { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';

// vitest 의 jsdom 환경은 AbortController/AbortSignal 을 jsdom 것으로 바꾸고, Node(undici) Request 는 그 signal 을
// 받지 않는다(TypeError: Expected signal ... to be an instance of AbortSignal). react-router 는 내비게이션마다
// `new Request(url, { signal })` 를 만들므로, 테스트 환경에서는 signal 을 떼고 Request 를 만든다.
// 내비게이션 취소 전파는 이 테스트들이 검증하지 않는다.
const NodeRequest = globalThis.Request;
globalThis.Request = class extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    const { signal: _signal, ...rest } = init ?? {};
    super(input, rest);
  }
} as typeof Request;

/** useBlocker 를 쓰는 컴포넌트 테스트용. '*' 하나에 ui 를 마운트한다. */
export function renderWithDataRouter(ui: ReactElement, { initialPath = '/' }: { initialPath?: string } = {}) {
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: [initialPath] });
  return { router, ...render(<RouterProvider router={router} />) };
}
