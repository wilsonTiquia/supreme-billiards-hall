import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react';
import { Link, type LinkProps } from 'react-router-dom';

type Variant = 'primary' | 'secondary' | 'tertiary' | 'danger';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  ref?: Ref<HTMLButtonElement>;
  variant?: Variant;
  pending?: boolean;
  children: ReactNode;
}

/* Filled actions use a theme-aware label; gold retains its separate ink token.
   `hit` holds the 44px minimum. */
const variants: Record<Variant, string> = {
  primary: 'bg-green text-on-action hover:brightness-110 disabled:brightness-75',
  secondary: 'bg-raised text-text border border-border hover:border-text-dim',
  tertiary: 'bg-transparent text-text hover:bg-raised underline underline-offset-4',
  danger: 'bg-danger text-on-action hover:brightness-110 disabled:brightness-75',
};

const base = 'hit inline-flex items-center justify-center gap-2 rounded-lg px-4 text-body font-semibold transition disabled:cursor-not-allowed disabled:opacity-60';

/** Navigation uses a real router link, including browser open-in-new-tab behavior. */
export function ButtonLink({ variant = 'primary', className = '', ...props }: LinkProps & { variant?: Variant }) {
  return <Link {...props} className={`${base} ${variants[variant]} ${className}`} />;
}

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
      className={`${base} ${variants[variant]} ${className}`}
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
