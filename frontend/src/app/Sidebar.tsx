import { useEffect, useRef, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { Wordmark } from '@/components/Wordmark';
import { ThemeIcon } from '@/components/ThemeIcon';
import { Icon, type IconName } from '@/components/Icon';
import type { Theme } from './ThemeProvider';

interface NavItem { to: string; label: string; icon: IconName }
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
  const [hint, setHint] = useState<{ label: string; top: number; left: number } | null>(null);
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
  const role = user?.role === 'ADMIN' ? 'Admin' : 'Employee';
  function showHint(target: HTMLElement, label: string) {
    if (!collapsed) return;
    const box = target.getBoundingClientRect();
    setHint({ label, top: Math.max(8, Math.min(box.y, window.innerHeight - 52)), left: navRef.current!.getBoundingClientRect().right + 8 });
  }
  return (
    <nav ref={navRef} aria-label="Main" aria-hidden={drawer && !open ? true : undefined} inert={drawer && !open ? true : undefined}
      className={`flex h-dvh shrink-0 flex-col border-r border-border bg-surface p-3 ${drawer
        ? `fixed inset-y-0 left-0 z-50 w-80 max-w-[85vw] transition-transform ${open ? 'translate-x-0' : '-translate-x-full'}`
        : `sticky top-0 ${collapsed ? 'w-28 px-2' : 'w-72'}`}`}>
      <div className="flex shrink-0 items-center">
        <Link to={workspace === 'admin' ? '/dashboard' : '/floor'} onClick={drawer ? onClose : undefined}
          className="hit flex min-w-11 flex-1 items-center rounded-lg" aria-label="Supreme Billiard Hall">
          <Wordmark compact={collapsed} />
        </Link>
        <button type="button" onClick={drawer ? onClose : onToggleCollapsed} className={toolStyle}
          aria-label={drawer ? 'Close the menu' : collapsed ? 'Expand the menu' : 'Collapse the menu'}>
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden>
            {drawer ? <path d="m6 6 12 12M6 18 18 6" /> : <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /><path d={collapsed ? 'm13 9 3 3-3 3' : 'm16 9-3 3 3 3'} /></>}
          </svg>
        </button>
      </div>

      {user?.role === 'ADMIN' ? (
        <div className="mt-3 grid shrink-0 grid-cols-2 rounded-xl bg-raised p-1" role="group" aria-label="Workspace">
          {(['floor', 'admin'] as const).map((mode) => (
            <Link key={mode} to={mode === 'admin' ? '/dashboard' : '/floor'} onClick={drawer ? onClose : undefined} aria-current={workspace === mode ? 'true' : undefined}
              aria-label={mode === 'admin' ? 'Admin' : 'Floor'} title={mode === 'admin' ? 'Admin workspace' : 'Floor workspace'}
              className={`hit flex min-w-11 items-center justify-center gap-2 rounded-lg text-body font-semibold ${workspace === mode ? 'bg-bg text-text shadow-sm' : 'text-text-dim hover:text-text'}`}>
              {collapsed ? <Icon name={mode === 'admin' ? 'dashboard' : 'floor'} /> : mode === 'admin' ? 'Admin' : 'Floor'}
            </Link>
          ))}
        </div>
      ) : null}

      {/* Only destinations scroll: workspace and account controls stay within reach on short screens. */}
      <div className="my-3 flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto overscroll-contain p-1" onScroll={event => {
        const focused = document.activeElement;
        const viewport = event.currentTarget.getBoundingClientRect();
        if (focused instanceof HTMLElement && event.currentTarget.contains(focused)) {
          const box = focused.getBoundingClientRect();
          if (box.top >= viewport.top && box.bottom <= viewport.bottom) {
            showHint(focused, focused.getAttribute('aria-label') ?? '');
            return;
          }
        }
        setHint(null);
      }}>
        {groups.map((group) => (
          <section key={group.label} aria-label={group.label}>
            <h2 className={collapsed ? 'sr-only' : 'mb-1 px-2 text-[11px] font-semibold uppercase tracking-widest text-text-dim'}>{group.label}</h2>
            <div className="flex flex-col gap-1">
              {group.items.map((item) => (
                <NavLink key={item.to} to={item.to} aria-label={item.label}
                  onMouseEnter={event => showHint(event.currentTarget, item.label)} onMouseLeave={() => setHint(null)}
                  onFocus={event => showHint(event.currentTarget, item.label)} onBlur={() => setHint(null)}
                  onClick={() => { setHint(null); if (drawer) onClose(); }} onKeyDown={event => { if (event.key === 'Escape') setHint(null); }}
                  className={({ isActive }) => `hit flex items-center gap-3 rounded-lg px-3 py-1 text-body leading-5 transition ${collapsed ? 'justify-center' : ''} ${isActive ? 'bg-green font-semibold text-bg' : 'text-text-dim hover:bg-raised hover:text-text'}`}>
                  <Icon name={item.icon} />
                  <span className={collapsed ? 'sr-only' : ''}>{item.label}</span>
                </NavLink>
              ))}
            </div>
          </section>
        ))}
      </div>
      {collapsed && hint ? <span aria-hidden="true" style={{ top: hint.top, left: hint.left }}
        className="pointer-events-none fixed z-50 rounded-lg border border-border bg-surface px-3 py-2 text-body text-text shadow-lg">{hint.label}</span> : null}

      <div className="flex shrink-0 items-center gap-1 border-t border-border pt-2">
        <Link to="/account" onClick={drawer ? onClose : undefined} aria-label={`Your account: ${user?.fullName ?? 'Account'}, ${role}`}
          title={`${user?.fullName ?? 'Account'} · ${role}`} className={`hit flex min-w-0 flex-1 items-center gap-3 rounded-lg py-2 hover:bg-raised ${collapsed ? 'justify-center' : 'px-2'}`}>
          <span aria-hidden className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-raised text-label text-text">
            {user?.fullName?.charAt(0) ?? 'U'}
          </span>
          <span className={collapsed ? 'sr-only' : 'min-w-0'}>
            <span className="block truncate text-body font-semibold text-text">{user?.fullName ?? 'Account'}</span>
            <span className="block truncate text-label text-text-dim">{role}</span>
          </span>
        </Link>
        <button type="button" onClick={onToggleTheme} className={toolStyle}
          aria-label={theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme'}>
          <ThemeIcon to={theme === 'dark' ? 'light' : 'dark'} />
        </button>
      </div>
    </nav>
  );
}
