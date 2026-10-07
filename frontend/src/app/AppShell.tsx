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

/** The counter's destinations, shared by expanded navigation and the icon rail. */
const COUNTER: NavGroup[] = [
  {
    label: 'At the counter',
    items: [
      { to: '/floor', label: 'Floor', icon: 'floor' },
      { to: '/quick-sale', label: 'Quick sale', icon: 'quick-sale' },
    ],
  },
  {
    label: 'Close the night',
    items: [
      // Beside End of day rather than up with the floor: money paid out is an occasional
      // errand, and it is the drawer count it has to reconcile with.
      { to: '/expenses', label: 'Expenses', icon: 'expenses' },
      // Unpaid bills sit with the end-of-night tasks.
      { to: '/unsettled', label: 'Unsettled', icon: 'unsettled' },
      { to: '/end-of-day', label: 'End of day', icon: 'end-of-day' },
    ],
  },
];

/** Owner navigation, grouped by purpose. */
const ADMIN: NavGroup[] = [
  {
    label: 'Manage',
    items: [
      { to: '/dashboard', label: 'Dashboard', icon: 'dashboard' },
      // Beside the dashboard, because it is the same question over a longer span: the
      // dashboard answers "how did last night go", this answers "is the business making money".
      { to: '/admin/reports', label: 'Reports', icon: 'reports' },
      { to: '/admin/products', label: 'Products', icon: 'products' },
      { to: '/admin/stock', label: 'Stock', icon: 'stock' },
    ],
  },
  {
    label: 'Look up',
    items: [
      { to: '/admin/sales', label: 'Sales', icon: 'sales' },
      { to: '/admin/audit', label: 'Audit', icon: 'audit' },
    ],
  },
  {
    label: 'Set up',
    items: [
      { to: '/admin/categories', label: 'Categories', icon: 'categories' },
      { to: '/admin/tables', label: 'Tables', icon: 'floor' },
      { to: '/admin/customer-types', label: 'Customer types', icon: 'customer-types' },
      { to: '/admin/expense-categories', label: 'Expense categories', icon: 'expense-categories' },
      { to: '/admin/vouchers', label: 'Vouchers', icon: 'vouchers' },
      { to: '/admin/staff', label: 'Staff', icon: 'staff' },
      { to: '/admin/settings', label: 'Settings', icon: 'settings' },
    ],
  },
];

export function AppShell() {
  const { user } = useAuth();
  const { theme, toggle, setScreen } = useTheme();
  const { pathname } = useLocation();

  // Below 768px, navigation opens as a drawer so the page keeps the full width.
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

  // Account is shared by both workspaces; keep the navigation the user came from.
  const [lastWorkspace, setLastWorkspace] = useState<'floor' | 'admin'>(() => {
    try { return sessionStorage.getItem('supreme.workspace') === 'admin' ? 'admin' : 'floor'; }
    catch { return 'floor'; }
  });
  const workspace = pathname.startsWith('/account') ? lastWorkspace
    : pathname.startsWith('/admin') || pathname.startsWith('/dashboard') ? 'admin' : 'floor';
  const admin = user?.role === 'ADMIN' && workspace === 'admin';
  useEffect(() => {
    if (pathname.startsWith('/account')) setScreen(admin ? 'admin' : 'pos');
  }, [pathname, admin, setScreen]);
  useEffect(() => {
    setLastWorkspace(workspace);
    try { sessionStorage.setItem('supreme.workspace', workspace); } catch { /* optional preference */ }
  }, [workspace]);

  // Full labels by default; the compact menu keeps every destination accessible.
  const [collapsed, setCollapsed] = useState(false);

  const { data: businessDay } = useQuery({
    queryKey: queryKeys.businessDayCurrent,
    queryFn: () => request<BusinessDayStatus>('/business-day/current'),
    staleTime: 60_000,
  });

  const groups = admin ? ADMIN : user?.role === 'ADMIN'
    ? [...COUNTER, { label: 'Look up', items: [{ to: '/admin/sales', label: 'Sales', icon: 'sales' as const }] }]
    : COUNTER;
  const floorOverview = /^\/floor\/?$/.test(pathname);

  return (
    <div className="flex min-h-dvh bg-bg text-text">
      {/* Off-canvas below md, an ordinary column at and above it. */}
      {!desktop && drawerOpen ? (
        <button
          type="button"
          aria-label="Close the menu"
          tabIndex={-1}
          aria-hidden="true"
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
        workspace={admin ? 'admin' : 'floor'}
        drawer={!desktop}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />

      <div className="flex min-w-0 flex-1 flex-col" inert={!desktop && drawerOpen ? true : undefined}>
        {/* The phone's way in. Hidden from 768 up, where the rail is always present. */}
        <div className="flex items-center gap-3 border-b border-border px-4 py-2 md:hidden print:hidden">
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
        {/* Only the full floor overview needs this persistent trading-date context.
            Keep the shared query above available to the other screens. */}
        {businessDay && floorOverview ? (
          <header aria-label="Current business day" className="sticky top-0 z-20 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-border bg-bg px-4 py-3 md:px-6">
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

        <main className={`min-w-0 flex-1 p-4 md:p-6 ${floorOverview ? 'floor-content' : ''}`}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
