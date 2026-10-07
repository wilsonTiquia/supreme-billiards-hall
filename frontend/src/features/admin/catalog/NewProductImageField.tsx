import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { Button } from '@/components/Button';

/** Local-only until creation returns a product ID. Replaced object URLs are always released. */
export function NewProductImageField({ file, disabled, onChange, onInvalid }: {
  file: File | null;
  disabled: boolean;
  onChange: (file: File | null) => void;
  onInvalid: (invalid: boolean) => void;
}) {
  const picker = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!file) { setPreview(null); return; }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  function pick(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0];
    event.target.value = '';
    if (!selected) return;
    const problem = !['image/jpeg', 'image/png', 'image/webp'].includes(selected.type)
      ? 'Use a JPEG, PNG or WebP image.'
      : selected.size === 0 ? 'That file is empty. Choose another picture.'
      : selected.size > 2 * 1024 * 1024 ? 'The limit is 2 MB. Choose a smaller picture.' : null;
    setError(problem);
    onInvalid(Boolean(problem));
    onChange(problem ? null : selected);
  }

  function remove() {
    onChange(null);
    setError(null);
    onInvalid(false);
  }

  return <div>
    <p className="text-label uppercase text-text-dim">Picture <span className="normal-case">(optional)</span></p>
    <div className="mt-2 flex items-start gap-3">
      {preview ? <img src={preview} alt="Selected product picture" className="size-16 shrink-0 rounded-lg border border-border object-cover" /> : null}
      <div className="min-w-0 flex-1">
        <input ref={picker} type="file" aria-label="Choose product picture" tabIndex={-1}
          accept="image/jpeg,image/png,image/webp" disabled={disabled} onChange={pick} className="sr-only" />
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" disabled={disabled} onClick={() => picker.current?.click()}>
            {file ? 'Replace picture' : 'Add picture'}
          </Button>
          {file || error ? <Button type="button" variant="tertiary" disabled={disabled} onClick={remove}>Remove picture</Button> : null}
        </div>
        {file ? <p className="mt-1 break-all text-label text-text-dim">{file.name}</p> : null}
        <p className="mt-1 text-label text-text-dim">JPEG, PNG or WebP, up to 2 MB.</p>
      </div>
    </div>
    {error ? <p role="alert" className="mt-2 text-body text-danger">{error}</p> : null}
  </div>;
}
