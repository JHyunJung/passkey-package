import { useEffect, useRef, useState } from 'react';
import Dialog from './Dialog';

const IDLE_MS = 30 * 60 * 1000;    // 30 min
const COUNTDOWN_S = 60;

export default function IdleTimeout() {
  const [warn, setWarn] = useState(false);
  const [secs, setSecs] = useState(COUNTDOWN_S);
  const lastActive = useRef(Date.now());
  const tickRef = useRef<number | null>(null);
  const loggingOutRef = useRef(false);

  useEffect(() => {
    const bump = () => { lastActive.current = Date.now(); };
    const events: (keyof WindowEventMap)[] = ['mousemove', 'keydown', 'scroll', 'click'];
    events.forEach((e) => window.addEventListener(e, bump));

    const check = window.setInterval(() => {
      if (!warn && Date.now() - lastActive.current > IDLE_MS) {
        setWarn(true);
        setSecs(COUNTDOWN_S);
      }
    }, 10_000);

    return () => {
      events.forEach((e) => window.removeEventListener(e, bump));
      window.clearInterval(check);
    };
  }, [warn]);

  useEffect(() => {
    if (!warn) return;
    tickRef.current = window.setInterval(() => {
      setSecs((s) => {
        if (s <= 1) {
          logout();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => {
      if (tickRef.current) window.clearInterval(tickRef.current);
    };
  }, [warn]);

  function logout() {
    if (loggingOutRef.current) return;
    loggingOutRef.current = true;
    if (tickRef.current) {
      window.clearInterval(tickRef.current);
      tickRef.current = null;
    }
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    }).finally(() => {
      window.location.href = '/admin/login';
    });
  }

  function stay() {
    lastActive.current = Date.now();
    setWarn(false);
  }

  return (
    <Dialog
      open={warn}
      onClose={stay}
      closeOnScrim={false}
      title="자동 로그아웃 대기"
      sub={`${secs}초 후 보안을 위해 자동 로그아웃됩니다.`}
      footer={
        <>
          <button className="btn btn--outline" onClick={logout}>지금 로그아웃</button>
          <button className="btn btn--primary" onClick={stay}>계속 사용</button>
        </>
      }
    >
      <div className="banner banner--warning">
        30분 동안 활동이 감지되지 않았습니다. &ldquo;계속 사용&rdquo;을 누르면 세션이 갱신됩니다.
      </div>
    </Dialog>
  );
}
