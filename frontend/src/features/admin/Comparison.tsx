import { formatPesos } from '@/lib/money';

/**
 * How a figure moved against the one it is compared to. The one place the browser does
 * arithmetic on money, and deliberately so: both figures came from the server, the result
 * is a sentence, and it goes nowhere.
 *
 * Under one per cent is "about the same" in neutral text. "-0.4%" in red taught the eye that
 * every night was slightly bad, and a coloured arrow on a rounding difference is noise
 * dressed as news. Green and red are kept for a change worth acting on.
 */
export interface Change {
  tone: 'none' | 'same' | 'good' | 'bad';
  /** The line under the figure — "▲ 8% vs last Saturday". */
  text: string;
  /** The rounded, unsigned percentage when there is one, for building a sentence from. */
  percent: number | null;
}

export function describeChange({
  now,
  before,
  against,
  kind = 'money',
  goodWhen = 'up',
}: {
  now: number | null;
  before: number | null;
  /** "last Saturday", "July 2026". */
  against: string;
  kind?: 'money' | 'count';
  /** Takings up is good; an expense up is not. */
  goodWhen?: 'up' | 'down';
}): Change {
  if (now === null || before === null) return { tone: 'none', text: 'Nothing to compare', percent: null };
  if (before === 0) return { tone: 'none', text: `No trading ${against}`, percent: null };

  const change = now - before;
  const up = change > 0;
  const good = goodWhen === 'down' ? change < 0 : up;

  if (kind === 'count') {
    if (change === 0) return { tone: 'same', text: `Same as ${against}`, percent: null };
    return {
      tone: good ? 'good' : 'bad',
      text: `${up ? '▲' : '▼'} ${Math.abs(change).toLocaleString('en-PH')} vs ${against}`,
      percent: null,
    };
  }

  // A percentage of a negative base means nothing — a loss of 2,000 becoming a gain of 50,000
  // is not "up 2600%" — so a figure that can go negative is compared by amount instead.
  if (before < 0) {
    if (Math.abs(change) < 1) return { tone: 'same', text: `About the same as ${against}`, percent: null };
    return {
      tone: good ? 'good' : 'bad',
      text: `${up ? '▲' : '▼'} ${formatPesos(Math.abs(change))} vs ${against}`,
      percent: null,
    };
  }

  const percent = Math.round(Math.abs((change / before) * 100));
  if (Math.abs((change / before) * 100) < 1) {
    return { tone: 'same', text: `About the same as ${against}`, percent: null };
  }
  return { tone: good ? 'good' : 'bad', text: `${up ? '▲' : '▼'} ${percent}% vs ${against}`, percent };
}

/**
 * The comparison as the tail of a sentence: "8% better than last Saturday", "11% down on
 * July", "about the same as last Tuesday". Null when there is nothing to compare, so the
 * sentence can end without it.
 */
export function comparedClause(change: Change, against: string): string | null {
  switch (change.tone) {
    case 'same':
      return `about the same as ${against}`;
    case 'good':
      return change.percent === null ? `up on ${against}` : `${change.percent}% better than ${against}`;
    case 'bad':
      return change.percent === null ? `down on ${against}` : `${change.percent}% down on ${against}`;
    default:
      return null;
  }
}

export function toneClass(tone: Change['tone']): string {
  return tone === 'good' ? 'text-green' : tone === 'bad' ? 'text-danger' : 'text-text-dim';
}

/** The comparison as a line under a figure. */
export function ComparisonLine(props: Parameters<typeof describeChange>[0]) {
  const change = describeChange(props);
  return <p className={`mt-1 text-label ${toneClass(change.tone)}`}>{change.text}</p>;
}

/**
 * A headline figure: the number big, the label small beneath it, the comparison under that.
 * `size` picks the weight — the page's one answer is `hero`, the rest are `large`.
 */
export function BigFigure({
  label,
  value,
  size = 'large',
  tone,
  comparison,
}: {
  label: string;
  value: string;
  size?: 'hero' | 'large';
  /** Only a figure that is itself bad news — a loss — is coloured. */
  tone?: 'danger';
  comparison?: Parameters<typeof describeChange>[0];
}) {
  return (
    <div>
      <div
        className={`tabular ${size === 'hero' ? 'text-display sm:text-[3.5rem] sm:leading-none' : 'figure-large'} ${
          tone === 'danger' ? 'text-danger' : 'text-text'
        }`}
      >
        {value}
      </div>
      <div className="mt-1 text-label uppercase text-text-dim">{label}</div>
      {comparison ? <ComparisonLine {...comparison} /> : null}
    </div>
  );
}
