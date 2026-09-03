/**
 * The break, authored.
 *
 * This is what plays when the clip cannot: a codec missing, a media pipeline disabled, a file
 * that never arrives. It says the same thing in the same visual language as the rack watermark
 * on a free card — a racked triangle coming apart — so the moment reads whether or not the
 * video does, and nobody has to discover on a Friday that the flourish silently stopped
 * happening.
 *
 * Geometry matches RackMark on purpose. The mark a table wears while it is free is the mark
 * that breaks when it opens.
 */
const ROWS = [[0], [-1, 1], [-2, 0, 2], [-3, -1, 1, 3]];

export function RackBreak() {
  const balls = ROWS.flatMap((row, r) =>
    row.map((offset) => {
      const cx = 60 + offset * 8.5;
      const cy = 34 + r * 15;
      /*
       * Radially outward from the rack's centre of mass (60, 64), so the scatter reads as one
       * impact rather than as ten independent drifts, with a leftward bias because the cue
       * arrives from the right — the same direction the clip breaks in.
       */
      const dx = (cx - 60) * 2.9 - 16;
      const dy = (cy - 64) * 2.4;
      return { key: `${r}-${offset}`, cx, cy, dx, dy, delay: r * 22 };
    }),
  );

  return (
    <svg
      viewBox="0 0 120 104"
      aria-hidden
      className="h-full w-full stroke-green"
      fill="none"
      strokeWidth="2"
    >
      <g className="motion-break-cue">
        <path d="M14 88 L86 16" opacity="0.35" />
        <path d="M106 88 L34 16" opacity="0.35" />
      </g>
      <path d="M60 20 L98 88 L22 88 Z" strokeLinejoin="round" className="motion-break-frame" />
      {balls.map((ball) => (
        <circle
          key={ball.key}
          cx={ball.cx}
          cy={ball.cy}
          r="5.6"
          className="motion-break-ball"
          style={
            {
              '--dx': `${ball.dx.toFixed(1)}px`,
              '--dy': `${ball.dy.toFixed(1)}px`,
              animationDelay: `${ball.delay}ms`,
            } as React.CSSProperties
          }
        />
      ))}
    </svg>
  );
}
