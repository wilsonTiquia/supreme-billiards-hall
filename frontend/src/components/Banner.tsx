import type { ReactNode } from 'react';

type Tone = 'danger' | 'info' | 'warning';

const tones: Record<Tone, string> = {
  danger: 'border-danger/50 text-danger',
  info: 'border-info/50 text-info',
  warning: 'border-gold text-amount',
};

/** Inline, dismissible-by-context messaging. Never a modal that hides the floor. */
export function Banner({
  tone = 'info',
  children,
  actions,
}: {
  tone?: Tone;
  children: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={`flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-surface px-4 py-3 text-body ${tones[tone]}`}
    >
      <span>{children}</span>
      {actions}
    </div>
  );
}
