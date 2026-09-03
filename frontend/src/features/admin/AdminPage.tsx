import type { ReactNode } from 'react';
import { useScreenTheme } from '@/app/useTheme';
import { Banner } from '@/components/Banner';

/** Shared frame for the admin screens. Light theme, since this is read in daylight. */
export function AdminPage({
  title,
  intro,
  error,
  children,
}: {
  title: string;
  intro?: string;
  error?: string | null;
  children: ReactNode;
}) {
  useScreenTheme('admin');

  // Not a reading column. The owner opens this on the same wide monitor the floor runs on,
  // and the night band is a seven-hour span that wants the room. Still capped, because a
  // single table of numbers stretched edge to edge is harder to read, not easier — the
  // screens use the width by splitting into columns rather than by growing one of them.
  return (
    <div className="mx-auto max-w-[112rem]">
      <h1 className="text-heading text-text">{title}</h1>
      {intro ? <p className="mt-1 text-body text-text-dim">{intro}</p> : null}
      {error ? (
        <div className="mt-4">
          <Banner tone="danger">{error}</Banner>
        </div>
      ) : null}
      <div className="mt-6">{children}</div>
    </div>
  );
}
