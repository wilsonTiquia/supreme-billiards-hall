import { useEffect, useState } from 'react';

/**
 * Whether this machine has asked for less motion.
 *
 * Read live rather than once at module load: the venue machine is set up by someone other than
 * the person using it, and a preference changed in System Settings should take effect without
 * anyone knowing to restart the till.
 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => matches());

  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setReduced(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return reduced;
}

function matches(): boolean {
  // Guarded because a preview or a headless render may have no matchMedia at all, and a
  // missing API should mean "no preference expressed", not a crash on the floor.
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;
}

/**
 * Runs `onEnd` after `ms`, or the moment anyone touches the machine — whichever comes first.
 *
 * Both flourishes on this till are decoration over a working screen, and the person watching
 * them is usually the person who no longer needs to. Any key, click or wheel ends it: the
 * staff member reaching for the next table is the signal that the animation has outlived its
 * usefulness, and they should never have to wait for it or aim at a dismiss control.
 *
 * Captured on the window in the capture phase so the same gesture also does whatever it was
 * going to do. Skipping is never a click the operator has to spend.
 */
export function useDismissable(active: boolean, ms: number, onEnd: () => void) {
  useEffect(() => {
    if (!active) return;

    const end = () => onEnd();
    const timer = window.setTimeout(end, ms);
    const events: (keyof WindowEventMap)[] = ['keydown', 'pointerdown', 'wheel'];
    events.forEach((name) => window.addEventListener(name, end, { capture: true, passive: true }));

    return () => {
      window.clearTimeout(timer);
      events.forEach((name) => window.removeEventListener(name, end, { capture: true }));
    };
  }, [active, ms, onEnd]);
}
