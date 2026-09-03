import { forwardRef, type ReactNode } from 'react';

/**
 * Forwards a ref so a card can be measured or animated.
 *
 * The checkout morph needs the bill card's geometry at the moment of payment, and the receipt
 * card is the element that actually animates — both need a handle on the real node.
 */
export const Card = forwardRef<
  HTMLDivElement,
  { children: ReactNode; className?: string }
>(function Card({ children, className = '' }, ref) {
  return (
    <div ref={ref} className={`rounded-xl border border-border bg-surface p-6 ${className}`}>
      {children}
    </div>
  );
});
