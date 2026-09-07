import { Outlet, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/useAuth';
import { useMediaQuery, DESKTOP } from '@/lib/useMediaQuery';
import { useTheme } from './useTheme';
import { queryKeys } from '@/api/queryKeys';
import { request } from '@/api/client';
import type { BusinessDayStatus } from '@/api/types';
import { formatBusinessDate } from '@/lib/datetime';
import { Sidebar, type NavGroup } from './Sidebar';

/** The counter's destinations. Short enough to live on a collapsed rail. */
const COUNTER: NavGroup[] = [
  {
    emphasis: 'primary',
    items: [
      { to: '/floor', label: 'Floor', short: 'Fl' },
      { to: '/quick-sale', label: 'Quick sale', short: 'QS' },
    ],
  },
  {
    emphasis: 'quiet',
    items: [
      // Beside End of day rather than up with the floor: money paid out is an occasional
      // errand, and it is the drawer count it has to reconcile with.
      { to: '/expenses', label: 'Expenses', short: 'Ex' },
      // Who owes the hall money. Quiet rather than primary: it is checked when somebody walks
      // in to settle, not on every sale.
      { to: '/unsettled', label: 'Unsettled', short: 'Un' },
      { to: '/end-of-day', label: 'End of day', short: 'EOD' },
    ],
  },
];

/** The owner's eight, in the three groups they actually fall into. */
const ADMIN: NavGroup[] = [
  {
    emphasis: 'primary',
    items: [
      { to: '/dashboard', label: 'Dashboard', short: 'Db' },
      { to: '/admin/products', label: 'Products', short: 'Pr' },
      { to: '/admin/stock', label: 'Stock', short: 'St' },
    ],
  },
  {
    label: 'Look up',
    emphasis: 'quiet',
    items: [
      { to: '/admin/sales', label: 'Sales', short: 'Sa' },
      { to: '/admin/audit', label: 'Audit', short: 'Au' },
    ],
  },
  {
    label: 'Set up',
    emphasis: 'quiet',
    items: [
      { to: '/admin/categories', label: 'Categories', short: 'Ca' },
      { to: '/admin/tables', label: 'Tables', short: 'Tb' },
      { to: '/admin/customer-types', label: 'Customer types', short: 'CT' },
      { to: '/admin/expense-categories', label: 'Expense categories', short: 'EC' },
      { to: '/admin/staff', label: 'Staff', short: 'Sf' },
      { to: '/admin/settings', label: 'Settings', short: 'Se' },
    ],
  },
];

export function AppShell() {
  const { user, logout } = useAuth();
  const { theme, toggle } = useTheme();
  const { pathname } = useLocation();

  /*
   * Below 768px the rail becomes a drawer.
   *
   * A 256px column out of a 375px phone leaves 119px for the screen itself, which is why every
   * route overflowed sideways. A drawer rather than a bottom bar because the admin side has
   * nine destinations and a bottom bar would need a "More" that hides half of them — and the
   * phone case is the owner checking the dashboard from home, which is occasional navigation,
   * not rapid switching.
   *
   * At 768 and above nothing changes: the counter monitor gets exactly the shell it had.
   */
  const desktop = useMediaQuery(DESKTOP);
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Navigating is the end of needing the drawer.
  useEffect(() => setDrawerOpen(false), [pathname]);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  const admin = pathname.startsWith('/admin') || pathname.startsWith('/dashboard');

  /* Open by default on both. Measured rather than assumed: at the counter's width the 240px
     sidebar leaves 1904px for the floor grid, which is still three columns and ~630px cards —
     so it costs no column and the labels are worth more than the pixels, especially to staff
     learning this on Friday. It stays collapsible for a narrower screen, where the counter
     falls back to a rail of initials. */
  const [collapsed, setCollapsed] = useState(false);

  const { data: businessDay } = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: () => request<BusinessDayStatus>('/business-day/current'),
    staleTime: 60_000,
  });

  const groups = admin
    ? [...ADMIN, { emphasis: 'quiet' as const, items: [{ to: '/floor', label: 'Back to the floor', short: 'Fl' }] }]
    : user?.role === 'ADMIN'
      ? [...COUNTER, { emphasis: 'quiet' as const, items: [{ to: '/dashboard', label: 'Admin', short: 'Ad' }] }]
      : COUNTER;

  return (
    <div className="flex min-h-dvh bg-bg text-text">
      {/* Off-canvas below md, an ordinary column at and above it. */}
      {!desktop && drawerOpen ? (
        <button
          type="button"
          aria-label="Close the menu"
          className="fixed inset-0 z-40 bg-ink/60 md:hidden"
          onClick={() => setDrawerOpen(false)}
        />
      ) : null}

      <Sidebar
        groups={groups}
        // On a phone the drawer always shows full labels: it is not a space-constrained rail
        // there, it is a panel that covers the screen while it is open.
        collapsed={desktop ? collapsed : false}
        onToggleCollapsed={() => setCollapsed((c) => !c)}
        theme={theme}
        onToggleTheme={toggle}
        user={user ?? null}
        onSignOut={() => void logout()}
        drawer={!desktop}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        {/* The phone's way in. Hidden from 768 up, where the rail is always present. */}
        <div className="flex items-center gap-3 border-b border-border px-4 py-2 md:hidden">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open the menu"
            aria-expanded={drawerOpen}
            className="hit flex min-w-[44px] items-center justify-center rounded-lg border border-border bg-surface px-3 text-text"
          >
            <svg viewBox="0 0 20 14" className="h-4 w-5" aria-hidden fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M1 1h18M1 7h18M1 13h18" />
            </svg>
          </button>
          <span className="text-body font-semibold text-text">Supreme</span>
        </div>
        {/* The business day is context for what you are looking at, not somewhere to go. It
            belongs over the content, not in a rail of links. */}
        {businessDay ? (
          <header className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-border px-4 py-3 md:px-6">
            <div className="flex flex-wrap items-baseline gap-x-3">
              <span className="text-label uppercase text-text-dim">Business day</span>
              <span className="text-body text-text">
                {formatBusinessDate(businessDay.businessDate)}
              </span>
            </div>
            {/* The trading window is context, not news. It is the first thing to go when the
                line has to wrap on a phone. */}
            <span className="hidden text-label uppercase text-text-dim sm:inline">
              10:00 — 05:00 · Asia/Manila
            </span>
          </header>
        ) : null}

        <main className="min-w-0 flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
