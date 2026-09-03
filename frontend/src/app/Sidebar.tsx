import { Link, NavLink } from 'react-router-dom';
import { Wordmark } from '@/components/Wordmark';
import { ThemeIcon } from '@/components/ThemeIcon';

/**
 * Navigation down the side, treated as one composition rather than a stack of things that
 * happen to be there.
 *
 * Three problems this solves. Group labels used to sit at the same weight as the items under
 * them, so "Look up" and "Set up" read as two more links and the eye could not find where one
 * group ended — labels are now smaller, dimmer, letter-spaced and separated by a rule, and
 * items are inset from them. The current page used to be a tint; it now carries a solid fill
 * and a marker on the rail edge. And the bottom block used to be six similar rows crammed
 * under a void — the nav no longer stretches to push it down, so the two sit as one column
 * with real space between them instead of a gap that reads as something missing.
 */

export interface NavItem {
  to: string;
  label: string;
  /** Shown when the rail is collapsed. One or two characters. */
  short: string;
}

export interface NavGroup {
  label?: string;
  items: NavItem[];
  emphasis?: 'primary' | 'quiet';
}

export function Sidebar({
  groups,
  collapsed,
  onToggleCollapsed,
  theme,
  onToggleTheme,
  user,
  onSignOut,
  drawer = false,
  open = false,
  onClose,
}: {
  groups: NavGroup[];
  collapsed: boolean;
  onToggleCollapsed: () => void;
  theme: 'dark' | 'light';
  onToggleTheme: () => void;
  user: { fullName?: string; role?: string; branchName?: string | null } | null;
  onSignOut: () => void;
  /** Below md the rail is an off-canvas panel rather than a column in the flow. */
  drawer?: boolean;
  open?: boolean;
  onClose?: () => void;
}) {
  return (
    <nav
      aria-label="Main"
      // aria-hidden when closed so the whole menu is out of the tab order too, not merely
      // pushed off the side of the screen where a keyboard could still walk into it.
      aria-hidden={drawer && !open}
      className={`flex shrink-0 flex-col border-r border-border bg-surface py-4 ${
        drawer
          ? `fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] overflow-y-auto px-3 transition-transform duration-200 ${
              open ? 'translate-x-0' : '-translate-x-full'
            }`
          : `transition-[width] ${collapsed ? 'w-[4.5rem] px-2' : 'w-64 px-3'}`
      }`}
    >
      {drawer ? (
        <button
          type="button"
          onClick={onClose}
          className="hit mb-2 self-end rounded-lg border border-border px-4 text-label uppercase text-text"
        >
          Close
        </button>
      ) : null}
      <Link
        to="/floor"
        className={`hit flex items-center rounded-lg ${collapsed ? 'justify-center' : 'px-2'}`}
        aria-label="Supreme Billiard Hall — the floor"
      >
        <Wordmark compact={collapsed} />
      </Link>

      {/* The nav takes only the height it needs. It used to stretch, which is what put a void
          between the last link and everything below it. */}
      <div className="mt-6 flex flex-col gap-5 overflow-y-auto">
        {groups.map((group, index) => (
          <div key={group.label ?? index}>
            {group.label && !collapsed ? (
              // A rule plus a smaller, wider-tracked label: the boundary is a line, not a
              // guess about which row is a heading.
              <div className="mb-2 flex items-center gap-2 px-2">
                <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.14em] text-text-dim">
                  {group.label}
                </span>
                <span className="h-px flex-1 bg-border" aria-hidden />
              </div>
            ) : null}
            {group.label && collapsed ? (
              <div className="mx-2 mb-2 h-px bg-border" aria-hidden />
            ) : null}

            <div className={`flex flex-col gap-1 ${group.label && !collapsed ? 'pl-1' : ''}`}>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  title={collapsed ? item.label : undefined}
                  className={({ isActive }) =>
                    `hit relative flex items-center rounded-lg transition ${
                      collapsed ? 'justify-center' : 'px-3'
                    } ${
                      isActive
                        ? 'bg-green text-ink text-body font-semibold'
                        : group.emphasis === 'primary'
                          ? 'bg-raised text-text text-body font-semibold hover:brightness-110'
                          : 'text-text-dim text-body hover:bg-raised hover:text-text'
                    }`
                  }
                >
                  {({ isActive }) => (
                    <>
                      {/* A marker on the rail edge, so the current page is findable without
                          reading — the same trick the floor cards use. */}
                      {isActive ? (
                        <span
                          className="absolute -left-3 top-1/2 h-6 w-1 -translate-y-1/2 rounded-r bg-green"
                          aria-hidden
                        />
                      ) : null}
                      {collapsed ? item.short : item.label}
                    </>
                  )}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* Housekeeping. Pushed to the bottom by this spacer rather than by a stretched nav, so
          the empty space belongs to the composition instead of appearing inside it. */}
      <div className="flex-1" aria-hidden />

      <div className="flex flex-col gap-1 border-t border-border pt-3">
        {!collapsed && user ? (
          <div className="px-2 pb-1">
            <div className="truncate text-body text-text">{user.fullName}</div>
            {/* Role and branch on their own lines: "ADMIN · Supreme Billiard Hall" on one line
                truncated to "SUPREME BIL…" in a 256px rail. */}
            <div className="text-label uppercase text-text-dim">{user.role}</div>
            <div className="truncate text-label text-text-dim">{user.branchName ?? 'No branch'}</div>
          </div>
        ) : null}

        <div className={`flex gap-1 ${collapsed ? 'flex-col' : ''}`}>
          <button
            type="button"
            onClick={onToggleTheme}
            aria-label={theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme'}
            className={`hit flex items-center justify-center rounded-lg text-text-dim transition hover:bg-raised hover:text-text ${
              collapsed ? '' : 'w-11'
            }`}
          >
            <ThemeIcon to={theme === 'dark' ? 'light' : 'dark'} />
          </button>
          {/* Collapsing is a desktop affordance. In the drawer there is nothing to collapse
              into — the panel is already the whole menu, and a rail of initials inside an
              overlay would be a control that does nothing useful. */}
          {drawer ? null : (
            <button
              type="button"
              onClick={onToggleCollapsed}
              aria-label={collapsed ? 'Expand the menu' : 'Collapse the menu'}
              className={`hit flex items-center justify-center rounded-lg text-label uppercase text-text-dim transition hover:bg-raised hover:text-text ${
                collapsed ? '' : 'w-11'
              }`}
            >
              {collapsed ? '»' : '«'}
            </button>
          )}
          <button
            type="button"
            onClick={onSignOut}
            className={`hit flex flex-1 items-center rounded-lg text-label uppercase text-text-dim transition hover:bg-raised hover:text-text ${
              collapsed ? 'justify-center' : 'justify-center px-3'
            }`}
          >
            {collapsed ? 'Out' : 'Sign out'}
          </button>
        </div>
      </div>
    </nav>
  );
}
