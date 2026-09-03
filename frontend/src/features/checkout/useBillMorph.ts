import { useEffect, useRef } from 'react';
import { usePrefersReducedMotion } from '@/lib/motion';

/** Where the bill card was sitting at the instant the payment was taken. */
export interface CardRect {
  x: number;
  width: number;
}

/*
 * Timings. The whole thing is 600ms, inside the 400–700ms band.
 *
 * The travel finishes first and the chrome arrives over the top of its tail, so the card has
 * already found its place by the time the receipt's own furniture appears. Nothing waits for
 * anything else — every part is running by 260ms.
 */
const TRAVEL_MS = 440;
const CHROME_DELAY_MS = 200;
const CHROME_MS = 300;
const TEAR_DELAY_MS = 260;
const TEAR_MS = 340;
export const MORPH_MS = TEAR_DELAY_MS + TEAR_MS; // 600

/**
 * The bill becoming the receipt.
 *
 * Not a page transition and not an overlay: this animates the *real* receipt card, from where
 * the bill card was standing to where the receipt belongs. Same object, changing state — which
 * is what a receipt is, the bill turned into proof that it was paid.
 *
 * It animates `width` rather than `transform: scale` on purpose. The card narrows from 616 to
 * 448, and a 0.73 horizontal scale visibly squashes every glyph on it; real width keeps the
 * type honest and lets the money column close in under its own layout. Measured across that
 * range first — no line re-wraps at any width between the two, so nothing jumps.
 *
 * The receipt is live throughout. There is no overlay, no scrim and no pointer-events trap:
 * the thing being animated is the thing you can click, so Back to the floor works on frame one.
 * Any input calls finish(), which lands every animation exactly where it was already going —
 * skipping costs nothing and leaves nothing half-done.
 */
export function useBillMorph(
  /*
   * The node itself, not a ref object.
   *
   * The receipt shows a spinner while it loads, so the card does not exist on first render —
   * and a ref object is referentially stable, so an effect keyed on one never re-runs when the
   * card finally mounts. Taking the node as state means this fires exactly when the card
   * appears, which is the moment the morph is about.
   */
  element: HTMLElement | null,
  from: CardRect | null,
  enabled: boolean,
) {
  const reduced = usePrefersReducedMotion();
  // One morph per arrival. A re-render must not replay it.
  const played = useRef(false);

  useEffect(() => {
    if (!enabled || reduced || !from || !element || played.current) return;
    played.current = true;

    const to = element.getBoundingClientRect();
    const dx = from.x - to.x;
    const dw = from.width - to.width;

    /*
     * Only travel if there is a journey worth watching.
     *
     * On a phone the checkout and the receipt are the same single column, so the bill card and
     * the receipt card occupy the identical box — measured at 375: both x 16, both 343 wide.
     * Animating that is animating nothing. At 768 it is worse than nothing: an 8px slide over
     * 440ms reads as a card that lagged, not as a card that moved on purpose.
     *
     * Below these thresholds the card simply is where it belongs and only the receipt's own
     * furniture arrives — which is still true, still meaningful, and costs no movement.
     */
    const worthTravelling = Math.abs(dx) >= 24 || Math.abs(dw) >= 32;

    const running: Animation[] = [];

    if (worthTravelling) {
      element.style.willChange = 'transform, width';
      running.push(
        element.animate(
          [
            { transform: `translateX(${dx}px)`, width: `${from.width}px` },
            { transform: 'translateX(0px)', width: `${to.width}px` },
          ],
          { duration: TRAVEL_MS, easing: 'cubic-bezier(0.22, 0.61, 0.36, 1)' },
        ),
      );
    }

    /*
     * The parts the bill did not have: the hall's name at the top, the disclaimer at the
     * bottom, and the button row — the bill had Take payment where those buttons now are.
     * They arrive from the direction the paper would have fed from, which is what makes it
     * read as one slip rather than several fades.
     *
     * Queried from the card's container, not the card, because the buttons sit outside it.
     * Without this they stood fully formed and centred while the card was still over on the
     * left, which read as two unrelated things rather than one becoming the other.
     */
    const scope = element.parentElement ?? element;
    scope.querySelectorAll('[data-receipt-chrome]').forEach((node, index) => {
      running.push(
        node.animate(
          [
            { opacity: 0, transform: `translateY(${index === 0 ? -10 : 10}px)` },
            { opacity: 1, transform: 'translateY(0px)' },
          ],
          {
            duration: CHROME_MS,
            delay: CHROME_DELAY_MS,
            easing: 'cubic-bezier(0.22, 0.61, 0.36, 1)',
            fill: 'backwards',
          },
        ),
      );
    });

    // The tear: the perforated edge drawing outward from the middle. The one flourish.
    const tear = element.querySelector('[data-receipt-tear]');
    if (tear) {
      running.push(
        tear.animate(
          [
            { transform: 'scaleX(0)', opacity: 0 },
            { transform: 'scaleX(1)', opacity: 1 },
          ],
          {
            duration: TEAR_MS,
            delay: TEAR_DELAY_MS,
            easing: 'cubic-bezier(0.16, 0.9, 0.3, 1)',
            fill: 'backwards',
          },
        ),
      );
    }

    const finishNow = () => running.forEach((a) => a.finish());
    const events: (keyof WindowEventMap)[] = ['keydown', 'pointerdown', 'wheel'];
    events.forEach((name) =>
      window.addEventListener(name, finishNow, { capture: true, passive: true }),
    );

    const clean = () => {
      element.style.willChange = '';
    };
    Promise.all(running.map((a) => a.finished)).then(clean).catch(clean);

    return () => {
      events.forEach((name) => window.removeEventListener(name, finishNow, { capture: true }));
      running.forEach((a) => {
        if (a.playState !== 'finished') a.finish();
      });
      clean();
    };
  }, [element, from, enabled, reduced]);
}
