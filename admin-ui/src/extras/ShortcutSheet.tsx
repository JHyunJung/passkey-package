import { Button, Dialog } from '@/ui';
import { isMacPlatform, paletteShortcutLabel } from '@/lib/platform';
import { GO_SHORTCUTS } from './useGoShortcuts';

export type ShortcutRow = { keys: string; alt?: string; desc: string };

/** 실제로 동작하는 단축키만 나열한다. G 이동은 PLATFORM 운영자에게만 켜져 있다(useGoShortcuts). */
export function shortcutRows({ mac, platform }: { mac: boolean; platform: boolean }): ShortcutRow[] {
  const rows: ShortcutRow[] = [
    {
      keys: paletteShortcutLabel(mac),
      alt: mac ? `Windows · ${paletteShortcutLabel(false)}` : `Mac · ${paletteShortcutLabel(true)}`,
      desc: '명령 팔레트 열기',
    },
  ];
  if (platform) {
    rows.push(
      { keys: GO_SHORTCUTS.tenants, desc: 'Tenants 목록으로 이동' },
      { keys: GO_SHORTCUTS.activity, desc: '활동으로 이동' },
      { keys: GO_SHORTCUTS['audit-chain'], desc: '감사 체인으로 이동' },
      { keys: GO_SHORTCUTS.settings, desc: '설정으로 이동' },
    );
  }
  rows.push(
    { keys: '?', desc: '이 단축키 목록' },
    { keys: '← →', desc: '탭 사이 이동' },
    { keys: 'Enter', desc: '표에서 선택한 행 열기' },
    { keys: 'Esc', desc: '다이얼로그 · 메뉴 닫기' },
  );
  return rows;
}

/** 키보드 단축키 시트. `?` 키와 상단 바 도움말 메뉴에서 연다. */
export function ShortcutSheet({ open, onClose, platform }: { open: boolean; onClose: () => void; platform: boolean }) {
  const rows = shortcutRows({ mac: isMacPlatform(), platform });
  return (
    <Dialog open={open} onClose={onClose} title="키보드 단축키"
      footerStart={<span className="hint" style={{ margin: 0 }}>입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.</span>}
      footer={<Button onClick={onClose}>닫기</Button>}>
      <table className="table">
        <caption className="sr-only">키보드 단축키 목록</caption>
        <tbody>
          {rows.map((r) => (
            <tr key={r.desc}>
              <td style={{ width: 180 }}>
                <kbd>{r.keys}</kbd>
                {r.alt && <span className="hint" style={{ margin: '0 0 0 8px' }}>{r.alt}</span>}
              </td>
              <td>{r.desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Dialog>
  );
}
