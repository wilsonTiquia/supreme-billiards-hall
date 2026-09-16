import { useEffect, useRef } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { Wordmark } from '@/components/Wordmark';
import { ThemeIcon } from '@/components/ThemeIcon';
import type { Theme } from './ThemeProvider';

interface NavItem { to: string; label: string; short: string }
export interface NavGroup { label: string; items: NavItem[] }

export function Sidebar({ groups, collapsed, onToggleCollapsed, theme, onToggleTheme, user,
  workspace, drawer = false, open = false, onClose,
}: {
  groups: NavGroup[];
  collapsed: boolean;
  onToggleCollapsed: () => void;
  theme: Theme;
  onToggleTheme: () => void;
  user: { fullName?: string; role?: string; branchName?: string | null } | null;
  workspace: 'floor' | 'admin';
  drawer?: boolean;
  open?: boolean;
  onClose: () => void;
}) {
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!drawer || !open) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const nav = navRef.current;
    nav?.querySelector<HTMLButtonElement>('button[aria-label="Close the menu"]')?.focus();
    const trapFocus = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || !nav) return;
      const items = Array.from(nav.querySelectorAll<HTMLElement>('a[href], button:not([disabled])'));
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    nav?.addEventListener('keydown', trapFocus);
    return () => { nav?.removeEventListener('keydown', trapFocus); if (previous?.isConnected) previous.focus(); };
  }, [drawer, open]);
  const toolStyle = 'hit flex w-11 shrink-0 items-center justify-center rounded-lg text-text-dim hover:bg-raised hover:text-text';
  return (
    <nav ref={navRef} aria-label="Main" aria-hidden={drawer && !open ? true : undefined} inert={drawer && !open ? true : undefined}
      className={`flex h-dvh shrink-0 flex-col border-r border-border bg-surface p-3 ${drawer
        ? `fixed inset-y-0 left-0 z-50 w-80 max-w-[85vw] overflow-y-auto transition-transform ${open ? 'translate-x-0' : '-translate-x-full'}`
        : `sticky top-0 overflow-y-auto ${collapsed ? 'w-36 px-2' : 'w-80'}`}`}>
      <div className="flex shrink-0 items-center">
        <Link to={workspace === 'admin' ? '/dashboard' : '/floor'}
          className="hit flex min-w-0 flex-1 items-center rounded-lg" aria-label="Supreme Billiard Hall">
          <Wordmark compact={collapsed} />
        </Link>
        <button type="button" onClick={onToggleTheme} className={toolStyle}
          aria-label={theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme'}>
          <ThemeIcon to={theme === 'dark' ? 'light' : 'dark'} />
        </button>
        <button type="button" onClick={drawer ? onClose : onToggleCollapsed} className={toolStyle}
          aria-label={drawer ? 'Close the menu' : collapsed ? 'Expand the menu' : 'Collapse the menu'}>
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden>
            {drawer ? <path d="m6 6 12 12M6 18 18 6" /> : <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /><path d={collapsed ? 'm13 9 3 3-3 3' : 'm16 9-3 3 3 3'} /></>}
          </svg>
        </button>
      </div>

      {user?.role === 'ADMIN' ? (
        <div className="mt-4 grid shrink-0 grid-cols-2 rounded-xl bg-raised p-1" role="group" aria-label="Workspace">
          {(['floor', 'admin'] as const).map((mode) => (
            <Link key={mode} to={mode === 'admin' ? '/dashboard' : '/floor'} aria-current={workspace === mode ? 'true' : undefined}
              className={`hit flex items-center justify-center rounded-lg text-body font-semibold ${workspace === mode ? 'bg-bg text-text shadow-sm' : 'text-text-dim hover:text-text'}`}>
              {mode === 'admin' ? 'Admin' : 'Floor'}
            </Link>
          ))}
        </div>
      ) : null}

      {/* Two columns keep all destinations and 44px targets visible on a 720px counter display. */}
      <div className="my-4 flex shrink-0 flex-col gap-4">
        {groups.map((group) => (
          <section key={group.label} aria-label={group.label}>
            <h2 className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-widest text-text-dim">{group.label}</h2>
            <div className="grid grid-cols-2 gap-1">
              {group.items.map((item) => (
                <NavLink key={item.to} to={item.to} title={collapsed ? item.label : undefined} aria-label={item.label}
                  className={({ isActive }) => `hit flex items-center rounded-lg px-3 py-1 text-body leading-5 transition ${collapsed ? 'justify-center' : ''} ${isActive ? 'bg-green font-semibold text-bg' : 'text-text-dim hover:bg-raised hover:text-text'}`}>
                  {collapsed ? item.short : item.label}
                </NavLink>
              ))}
            </div>
          </section>
        ))}
      </div>

      <div className="mt-auto shrink-0 border-t border-border pt-2">
        <Link to="/account" title="Your account" className="hit flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-raised">
          <span aria-hidden className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-raised text-label text-text">
            {user?.fullName?.charAt(0) ?? 'U'}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-body font-semibold text-text">{user?.fullName ?? 'Account'}</span>
            {!collapsed ? <span className="block truncate text-label text-text-dim">{user?.role === 'ADMIN' ? 'Admin' : 'Employee'} · {user?.branchName ?? 'No branch'}</span> : null}
          </span>
        </Link>
      </div>
    </nav>
  );
}
