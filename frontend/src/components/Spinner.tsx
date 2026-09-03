export function Spinner({ label }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-text-dim" role="status">
      <span
        aria-hidden
        className="size-4 animate-spin rounded-full border-2 border-border border-t-green"
      />
      {label ? <span className="text-body">{label}</span> : <span className="sr-only">Loading</span>}
    </span>
  );
}
