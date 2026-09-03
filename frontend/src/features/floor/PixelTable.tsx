/**
 * A pool table seen from above, drawn on an honest pixel grid.
 *
 * Hand-authored and committed — every rect sits on a 1-unit grid with crispEdges, and the SVG
 * is rendered at a fixed size so one grid unit lands on ~5 device pixels. That matters: pixel
 * art stretched to fill a container stops being pixel art and becomes soft blocks. It is a
 * small object in the corner of the card, not a background wash.
 *
 * Nothing is fetched. This ships inside the jar; the venue machine has no reliable internet.
 *
 * It carries the free card's idea one step on. A free table shows a RACKED triangle: waiting.
 * A running table shows the balls SPREAD and the cue down: the break has happened. The
 * illustration is the state rather than decoration applied to it.
 *
 * Static, deliberately. The one thing that moves on this screen is the timer, and it moves
 * because the movement carries information; ambient drift on every running card, all night,
 * would compete with the only motion that means anything.
 */

/** Balls after a break, in grid units. Placed by hand — a random scatter clusters badly and
 *  re-rolls on every render. */
const BALLS: Array<[number, number, 'solid' | 'stripe' | 'cue']> = [
  [14, 9, 'solid'],
  [18, 13, 'stripe'],
  [22, 7, 'solid'],
  [25, 15, 'stripe'],
  [29, 10, 'solid'],
  [33, 17, 'stripe'],
  [36, 12, 'solid'],
  [9, 14, 'cue'],
];

export function PixelTable() {
  return (
    <svg
      viewBox="0 0 48 28"
      width="264"
      height="154"
      aria-hidden
      shapeRendering="crispEdges"
    >
      {/* Rail, then felt inside it. */}
      <rect x="0" y="0" width="48" height="28" className="fill-pocket" />
      <rect x="1" y="1" width="46" height="26" className="fill-rail" />
      <rect x="3" y="3" width="42" height="22" className="fill-felt" />

      {/* Six pockets. Squares, because a circle on this grid would be a lie about the medium. */}
      {[[1, 1], [23, 1], [45, 1], [1, 25], [23, 25], [45, 25]].map(([x, y]) => (
        <rect key={`p${x}-${y}`} x={x} y={y} width="2" height="2" className="fill-pocket" />
      ))}

      {/* The rail sights — the same diamonds the dashboard borrows to mark its hours. */}
      {[9, 15, 33, 39].map((x) => (
        <g key={`s${x}`}>
          <rect x={x} y="1" width="1" height="1" className="fill-sight" />
          <rect x={x} y="26" width="1" height="1" className="fill-sight" />
        </g>
      ))}

      {BALLS.map(([x, y, kind]) => (
        <g key={`b${x}-${y}`}>
          <rect x={x} y={y} width="2" height="2" className="fill-ball" />
          {/* One lit pixel each: the lamp is overhead, so the highlight is top-left. */}
          <rect
            x={x}
            y={y}
            width="1"
            height="1"
            className={
              kind === 'stripe'
                ? 'fill-ball-stripe'
                : kind === 'cue'
                  ? 'fill-ball-lit'
                  : 'fill-ball-lit'
            }
          />
        </g>
      ))}

      {/* The cue, laid across the felt after the break. */}
      <rect x="6" y="20" width="26" height="1" className="fill-cue" />
      <rect x="32" y="20" width="3" height="1" className="fill-ball-lit" />
    </svg>
  );
}
