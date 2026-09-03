import { useEffect, useState } from 'react';

/**
 * One interval for the whole floor. Re-renders the subtree once a second so the counters
 * advance; the value itself means nothing beyond "time passed".
 */
export function useTicker(active = true): number {
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!active) return;
    const id = window.setInterval(() => setTick((t) => t + 1), 1000);
    return () => window.clearInterval(id);
  }, [active]);

  return tick;
}
