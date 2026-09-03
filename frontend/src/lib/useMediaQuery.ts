import { useEffect, useState } from 'react';

/**
 * A live media query, for the handful of places where a layout decision cannot be expressed in
 * CSS alone.
 *
 * Most responsive behaviour here belongs in Tailwind variants and stays there. This exists for
 * the cases where the *markup* differs rather than the styling — the sidebar renders labels or
 * initials, and no amount of CSS turns one into the other.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => read(query));

  useEffect(() => {
    const list = window.matchMedia(query);
    const onChange = () => setMatches(list.matches);
    onChange();
    list.addEventListener('change', onChange);
    return () => list.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}

function read(query: string): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(query).matches
    : true;
}

/** Tailwind's `md`. Below this the shell puts navigation behind a drawer. */
export const DESKTOP = '(min-width: 768px)';
