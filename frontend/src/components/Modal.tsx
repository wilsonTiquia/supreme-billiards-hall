import { useEffect, useRef, type ReactNode } from 'react';

/**
 * Always dismissible — Escape, the backdrop, or the close control. Staff must never be shut
 * out of the floor view by a dialog they cannot get rid of.
 */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);

  // Escape must always reach the CURRENT callback, so this re-subscribes whenever it changes.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  /* Focus moves into the dialog ONCE, when it opens, so the keyboard-first path works without
     reaching for a mouse.
     Empty deps deliberately. This used to share the effect above, keyed on `onClose` — and the
     callers pass an inline arrow, so `onClose` was a new function on every parent render. The
     floor re-renders every second to drive the table counters, which meant this ran every
     second and yanked the cursor out of whatever the operator was typing and back onto the
     first field. A re-render is never a reason to move someone's cursor. */
  useEffect(() => {
    panel.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus();
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/60 p-6 pt-[8vh]"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="w-full max-w-md rounded-xl border border-border bg-surface p-6 shadow-2xl"
      >
        <div className="mb-6 flex items-start justify-between gap-4">
          <h2 className="text-heading text-text">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="hit -mt-2 -mr-2 rounded-lg px-3 text-body text-text-dim hover:text-text"
          >
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
