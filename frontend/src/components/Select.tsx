import type { SelectHTMLAttributes } from 'react';
import { useId } from 'react';

interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: string;
  hint?: string;
}

export function Select({ label, hint, className = '', children, ...rest }: SelectProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-label uppercase text-text-dim">
        {label}
      </label>
      <select
        {...rest}
        id={id}
        className={`hit w-full rounded-lg border border-border bg-raised px-3 text-body text-text ${className}`}
      >
        {children}
      </select>
      {hint ? <p className="text-label text-text-dim">{hint}</p> : null}
    </div>
  );
}
