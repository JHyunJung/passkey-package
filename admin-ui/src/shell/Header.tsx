import { useNavigate } from 'react-router-dom';
import { Command, LogOut, User } from 'lucide-react';
import { useMe } from '@/me/MeContext';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { api } from '@/api/client';

type Props = { onOpenPalette: () => void };

export function Header({ onOpenPalette }: Props) {
  const { me } = useMe();
  const navigate = useNavigate();

  async function handleLogout() {
    try {
      // api.post 가 XSRF-TOKEN 쿠키를 자동으로 X-XSRF-TOKEN 헤더에 포함함
      await api.post('/admin/logout', {});
    } catch {
      /* 무시 — 어차피 로그인으로 이동 */
    }
    navigate('/login');
  }

  return (
    <header className="h-14 border-b border-border-subtle bg-surface flex items-center justify-between px-6 gap-4">
      <div className="text-[13px] text-text-mute">
        {me?.role === 'PLATFORM_OPERATOR' ? 'Platform Operator Console' : 'Tenant Admin Console'}
      </div>
      <div className="flex items-center gap-3">
        <Button variant="outline" size="sm" onClick={onOpenPalette} className="gap-2">
          <Command className="h-3.5 w-3.5" />
          <span className="text-[12px]">⌘K</span>
        </Button>
        {me?.role && (
          <Badge variant={me.role === 'PLATFORM_OPERATOR' ? 'accent' : 'teal'}>
            {me.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'TENANT'}
          </Badge>
        )}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="gap-2">
              <User className="h-3.5 w-3.5" />
              <span className="text-[12px]">{me?.email ?? '...'}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel>{me?.email}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="text-danger">
              <LogOut className="h-3.5 w-3.5 mr-2" /> Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
