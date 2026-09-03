/**
 * Sun and moon, drawn rather than imported — no icon package, and one glyph either way so the
 * control never reflows. It shows the theme you would switch TO, which is the convention
 * every phone uses and the one staff will already have.
 */
export function ThemeIcon({ to }: { to: 'light' | 'dark' }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="20"
      height="20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {to === 'light' ? (
        <>
          <circle cx="12" cy="12" r="4.2" />
          <path d="M12 2.6v2.2M12 19.2v2.2M4.2 12H2M22 12h-2.2M6.3 6.3 4.8 4.8M19.2 19.2l-1.5-1.5M17.7 6.3l1.5-1.5M4.8 19.2l1.5-1.5" />
        </>
      ) : (
        <path d="M20.5 14.6A8.6 8.6 0 1 1 9.4 3.5a7 7 0 0 0 11.1 11.1Z" />
      )}
    </svg>
  );
}
