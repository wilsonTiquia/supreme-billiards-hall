import type { InputHTMLAttributes, ReactNode } from 'react';
import { useId } from 'react';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  hint?: ReactNode;
  error?: string;
  /**
   * A fixed symbol shown inside the box, before the value — "₱" on a money field.
   *
   * Rendered as a sibling rather than baked into the value: a currency symbol the user has to
   * arrow past or accidentally deletes is worse than no symbol at all.
   */
  prefix?: string;
}

export function Field({ label, hint, error, prefix, className = '', ...rest }: FieldProps) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-label uppercase text-text-dim">
        {label}
      </label>
      <div className="relative">
        {prefix ? (
          <span
            className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-body text-text-dim"
            aria-hidden
          >
            {prefix}
          </span>
        ) : null}
        <input
          {...rest}
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={`hit w-full rounded-lg border bg-raised text-body text-text placeholder:text-text-dim/60 ${
            prefix ? 'pl-8 pr-3' : 'px-3'
          } ${error ? 'border-danger' : 'border-border'} ${className}`}
        />
      </div>
      {hint && !error ? (
        <p id={`${id}-hint`} className="text-label text-text-dim">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} className="text-label text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
