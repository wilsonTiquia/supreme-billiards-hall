import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'secondary' | 'danger';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  pending?: boolean;
  children: ReactNode;
}

/* Green and gold are fills with an ink label, never coloured text — the contrast rules in
   FRONTEND-SPEC §2. `hit` holds the 44px minimum. */
const variants: Record<Variant, string> = {
  primary: 'bg-green text-ink hover:brightness-110 disabled:brightness-75',
  secondary: 'bg-raised text-text border border-border hover:border-text-dim',
  danger: 'bg-danger text-ink hover:brightness-110 disabled:brightness-75',
};

export function Button({
  variant = 'primary',
  pending = false,
  disabled,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      className={`hit inline-flex items-center justify-center gap-2 rounded-lg px-4 text-body font-semibold transition disabled:cursor-not-allowed disabled:opacity-60 ${variants[variant]} ${className}`}
    >
      {pending ? <Spinner /> : null}
      {children}
    </button>
  );
}

function Spinner() {
  return (
    <span
      aria-hidden
      className="size-4 animate-spin rounded-full border-2 border-current/30 border-t-current"
    />
  );
}
