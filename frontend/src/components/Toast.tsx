import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';

const ToastContext = createContext<{ notify: (message: string) => void; dismiss: () => void } | null>(null);

/** App-wide success feedback. Errors requiring action remain beside their form. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [notice, setNotice] = useState<{ message: string; id: number } | null>(null);
  const [paused, setPaused] = useState(false);
  const { pathname } = useLocation();
  const dismiss = useCallback(() => setNotice(null), []);
  const notify = useCallback((message: string) => {
    setPaused(false);
    setNotice((previous) => ({ message, id: (previous?.id ?? 0) + 1 }));
  }, []);

  useEffect(() => { if (pathname === '/login') dismiss(); }, [pathname, dismiss]);
  useEffect(() => {
    if (!notice || paused) return;
    const timer = window.setTimeout(dismiss, 8000);
    return () => window.clearTimeout(timer);
  }, [notice, paused, dismiss]);

  return (
    <ToastContext.Provider value={{ notify, dismiss }}>
      {children}
      <div className="pointer-events-none fixed inset-x-4 bottom-4 z-[70] flex justify-end print:hidden" role="status" aria-live="polite" aria-atomic="true">
        {notice ? (
          <div key={notice.id} className="pointer-events-auto flex w-full max-w-md items-center gap-3 rounded-xl border border-border bg-surface p-3 text-body text-text shadow-xl"
            onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
            onFocus={() => setPaused(true)} onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setPaused(false); }}>
            <span aria-hidden className="text-green">✓</span>
            <span className="flex-1">{notice.message}</span>
            <button type="button" onClick={dismiss} aria-label="Dismiss notification" className="hit flex w-11 shrink-0 items-center justify-center rounded-lg text-text-dim hover:bg-raised">×</button>
          </div>
        ) : null}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside ToastProvider');
  return context;
}
