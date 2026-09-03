import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Last line of defence against a white screen. A render-time bug on one screen must not
 * take the counter offline mid-shift, so it degrades to a message and a way back.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Unhandled UI error', error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;

    return (
      <div className="flex min-h-dvh items-center justify-center bg-bg p-6">
        <div className="max-w-md rounded-xl border border-danger/50 bg-surface p-6">
          <h1 className="text-heading text-text">Something broke on this screen</h1>
          <p className="mt-3 text-body text-text-dim">
            The rest of the system is unaffected. Reload to carry on; if it keeps happening,
            note what you were doing and tell the owner.
          </p>
          <p className="mt-3 text-label text-text-dim">{this.state.error.message}</p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="hit mt-6 w-full rounded-lg bg-green px-4 text-body font-semibold text-ink"
          >
            Reload
          </button>
        </div>
      </div>
    );
  }
}
