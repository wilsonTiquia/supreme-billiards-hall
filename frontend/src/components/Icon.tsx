import type { ReactNode } from 'react';

const glyphs = {
  'chevron-left': <path d="m14 6-6 6 6 6" />,
  'chevron-right': <path d="m10 6 6 6-6 6" />,
  'chevron-down': <path d="m6 9 6 6 6-6" />,
  back: <path d="m10 5-7 7 7 7M3 12h18" />,
  overflow: <><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>,
  refresh: <><path d="M20 7v5h-5M4 17v-5h5" /><path d="M5.3 7a8 8 0 0 1 13.2-1L20 9M4 15l1.5 3A8 8 0 0 0 18.7 17" /></>,
  check: <path d="m5 12 4 4L19 6" />,
  warning: <><path d="m12 3 10 18H2L12 3ZM12 9v4M12 17h.01" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v6M12 7h.01" /></>,
  close: <path d="m6 6 12 12M6 18 18 6" />,
  floor: <><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M3 9h2M3 15h2M19 9h2M19 15h2M12 5v2M12 17v2" /></>,
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></>,
  reports: <><path d="M4 3v18h17M8 16v-5M13 16V6M18 16v-8" /></>,
  products: <><path d="m12 3 9 5v9l-9 5-9-5V8l9-5ZM3 8l9 5 9-5M12 13v9M7.5 5.5l9 5" /></>,
  sales: <><path d="M5 3h14v18l-3-2-4 2-4-2-3 2V3ZM8 7h8M8 11h8M8 15h4" /></>,
  audit: <><circle cx="10" cy="10" r="7" /><path d="m15 15 6 6M10 6v4l3 2" /></>,
  sun: <><circle cx="12" cy="12" r="4.2" /><path d="M12 2.6v2.2M12 19.2v2.2M4.2 12H2M22 12h-2.2M6.3 6.3 4.8 4.8M19.2 19.2l-1.5-1.5M17.7 6.3l1.5-1.5M4.8 19.2l1.5-1.5" /></>,
  moon: <path d="M20.5 14.6A8.6 8.6 0 1 1 9.4 3.5a7 7 0 0 0 11.1 11.1Z" />,
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof glyphs;

/** Decorative glyphs: put the accessible name on the containing button/link or adjacent text. */
export function Icon({ name, className = '' }: { name: IconName; className?: string }) {
  return <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
    focusable="false" className={`shrink-0 ${className}`}>{glyphs[name]}</svg>;
}
