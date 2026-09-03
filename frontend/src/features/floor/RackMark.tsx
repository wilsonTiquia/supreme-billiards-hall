/**
 * A racked triangle over crossed cues — the watermark on a table that is free.
 *
 * Drawn, not fetched: the venue machine may have no internet, and a stock photograph of
 * someone else's pool hall would say less about this one than a rack does. It is also the
 * honest signal for the state — an empty table is a racked table, waiting.
 *
 * Sits behind the card's own content at low opacity, so it reads as texture rather than as
 * something to press.
 */
export function RackMark({ premium = false }: { premium?: boolean }) {
  const ballRows = [
    [0],
    [-1, 1],
    [-2, 0, 2],
    [-3, -1, 1, 3],
  ];

  return (
    <svg
      viewBox="0 0 120 104"
      aria-hidden
      // Sized as a fraction of the head rather than in rem. The grid goes to three columns
      // at 1280px, where a fixed-size mark grows wide enough relative to the card to reach the
      // table numeral; at 28% there is always better than a third of the head clear on each
      // side, whatever the card width. max-h keeps it off the felt band on a short card.
      className={`h-auto w-[28%] max-h-[70%] ${premium ? 'stroke-gold' : 'stroke-green'}`}
      fill="none"
      strokeWidth="2"
    >
      {/* The cues, crossed behind the rack. */}
      <path d="M14 88 L86 16" opacity="0.35" />
      <path d="M106 88 L34 16" opacity="0.35" />
      {/* The triangle. */}
      <path d="M60 20 L98 88 L22 88 Z" strokeLinejoin="round" opacity="0.9" />
      {ballRows.map((row, r) =>
        row.map((offset) => (
          <circle
            key={`${r}-${offset}`}
            cx={60 + offset * 8.5}
            cy={34 + r * 15}
            r="5.6"
            opacity="0.8"
          />
        )),
      )}
    </svg>
  );
}
