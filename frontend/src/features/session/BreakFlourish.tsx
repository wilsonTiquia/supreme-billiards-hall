import { useCallback, useEffect, useRef, useState } from 'react';
import { usePrefersReducedMotion, useDismissable } from '@/lib/motion';
import { RackBreak } from './RackBreak';

/** Long enough for the impact and the scatter to resolve; the clip itself is 1.625s. */
const DURATION_MS = 1700;

/**
 * How long the clip gets to produce its first frame before we give up on it.
 *
 * If a machine cannot play it — a codec missing, a media pipeline disabled, a file that never
 * arrives — the authored break takes over. Waiting longer than this would mean the moment
 * arrives after the operator has moved on, and showing an empty fading rectangle would be
 * worse than either.
 */
const FIRST_FRAME_MS = 600;

/**
 * The break, played once behind the head of a table that has just been opened.
 *
 * A rack coming apart is what starting a session *is* — the clip earns its place by meaning the
 * thing it decorates, rather than by being motion for its own sake. It runs once per table, not
 * once per transaction, which is why this moment can carry a real clip where checkout cannot.
 *
 * It sits BEHIND the head's content at low opacity and is `aria-hidden`, so the table name, the
 * status and the money above it are never waiting on it and never obscured by it. The screen is
 * fully usable from the first frame: this is a layer on a working card, not a curtain in front
 * of one.
 */
export function BreakFlourish() {
  const reduced = usePrefersReducedMotion();
  const [done, setDone] = useState(reduced);
  // The fade only starts once there is actually a picture to fade.
  const [playing, setPlaying] = useState(false);
  // Set when the clip has had its chance and produced nothing.
  const [fallback, setFallback] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  const end = useCallback(() => setDone(true), []);
  useDismissable(!done, DURATION_MS, end);

  useEffect(() => {
    if (reduced || done || playing || fallback) return;
    const giveUp = window.setTimeout(() => setFallback(true), FIRST_FRAME_MS);
    return () => window.clearTimeout(giveUp);
  }, [reduced, done, playing, fallback]);

  // Pause on the way out so a dismissed clip is not still decoding behind a removed layer.
  useEffect(() => {
    if (done) videoRef.current?.pause();
  }, [done]);

  if (reduced || done) return null;

  if (fallback) {
    return (
      <div
        className="motion-break-fallback pointer-events-none absolute inset-0 flex items-center justify-center overflow-hidden"
        aria-hidden
      >
        <div className="h-[85%] w-auto" style={{ aspectRatio: '120 / 104' }}>
          <RackBreak />
        </div>
      </div>
    );
  }

  return (
    <div
      className={`pointer-events-none absolute inset-0 overflow-hidden ${
        playing ? 'motion-break' : 'opacity-0'
      }`}
      aria-hidden
    >
      <video
        ref={videoRef}
        src="/motion/break.mp4"
        // muted and playsInline are what make autoplay permitted at all; the file also has no
        // audio track, so there is nothing to unmute even by accident.
        muted
        playsInline
        autoPlay
        preload="auto"
        className="h-full w-full object-cover"
        onPlaying={() => setPlaying(true)}
        onEnded={end}
        // A missing or undecodable file must not leave a black box over the head.
        onError={end}
      />
    </div>
  );
}
