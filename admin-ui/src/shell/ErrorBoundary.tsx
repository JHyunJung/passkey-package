import { Component, type ReactNode } from 'react';

type Props = { children: ReactNode; fallback?: ReactNode };
type State = { hasError: boolean; message?: string };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(err: unknown): State {
    return { hasError: true, message: err instanceof Error ? err.message : String(err) };
  }

  componentDidCatch(err: unknown) {
    console.error('UI error boundary caught:', err);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback ?? (
        <div style={{ padding: 32, color: 'var(--text-mute)' }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text)', marginBottom: 6 }}>
            화면을 표시하는 중 문제가 발생했습니다.
          </div>
          <div style={{ fontSize: 13 }}>새로고침하거나 다른 메뉴로 이동해 보세요.</div>
        </div>
      );
    }
    return this.props.children;
  }
}
