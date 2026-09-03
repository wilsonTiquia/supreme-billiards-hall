/**
 * Placeholder wordmark — the gold-ringed 8 and the Supreme name, drawn rather than pasted.
 * SLOT FOR THE REAL LOGO: only JPEGs exist today. When the vector arrives, replace the svg
 * below and nothing else changes.
 */
export function Wordmark({
  size = 'md',
  compact = false,
}: {
  size?: 'md' | 'lg';
  // The mark alone, for a collapsed sidebar. The 8 carries the brand on its own.
  compact?: boolean;
}) {
  const ring = size === 'lg' ? 64 : 36;

  return (
    <div className="flex items-center gap-3">
      <svg
        width={ring}
        height={ring}
        viewBox="0 0 64 64"
        aria-hidden
        className="shrink-0"
      >
        <circle cx="32" cy="32" r="29" className="fill-brand" />
        <circle cx="32" cy="32" r="29" className="stroke-gold" fill="none" strokeWidth="3" />
        <text
          x="32"
          y="33"
          textAnchor="middle"
          dominantBaseline="central"
          className="fill-gold"
          fontSize="34"
          fontWeight="700"
          fontFamily="ui-sans-serif, system-ui, sans-serif"
        >
          8
        </text>
      </svg>
      {compact ? null : (
      <div className="leading-tight">
        <div
          className={`font-bold tracking-wide text-text ${size === 'lg' ? 'text-display' : 'text-heading'}`}
        >
          SUPREME
        </div>
        <div className="text-label uppercase text-text-dim">Billiard Hall</div>
      </div>
      )}
    </div>
  );
}
