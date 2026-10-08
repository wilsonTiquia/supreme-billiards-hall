import { useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Link, type LinkProps } from 'react-router-dom';
import { Button } from './Button';
import { Icon, type IconName } from './Icon';
import './controls.css';

type Item = {
  id: string;
  label: string;
  description?: string;
  icon?: IconName;
  disabled?: boolean;
  hidden?: boolean;
  danger?: boolean;
} & ({ onSelect: () => void; to?: never; state?: never } | { to: LinkProps['to']; state?: LinkProps['state']; onSelect?: never });

/** Callers supply permission/disabled state and keep confirmations in their action handlers. */
export function ActionMenu({ label, items, disabled = false }: {
  label: string; items: Item[]; disabled?: boolean;
}) {
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const firstFocus = useRef<'first' | 'last'>('first');
  const [open, setOpen] = useState(false);
  const visible = items.filter(item => !item.hidden);
  const unavailable = disabled || visible.length === 0;

  function close(restore = true) {
    panel.current?.hidePopover();
    setOpen(false);
    if (restore) trigger.current?.focus();
  }

  useLayoutEffect(() => {
    if (!open || unavailable) return;
    const menu = panel.current!;
    menu.showPopover();
    const position = () => {
      const rect = trigger.current!.getBoundingClientRect();
      menu.style.maxHeight = `${window.innerHeight - 24}px`;
      const size = menu.getBoundingClientRect();
      menu.style.left = `${Math.max(12, Math.min(rect.right - size.width, window.innerWidth - size.width - 12))}px`;
      const below = rect.bottom + 8;
      menu.style.top = `${Math.max(12, Math.min(below + size.height <= window.innerHeight - 12 ? below : rect.top - size.height - 8, window.innerHeight - size.height - 12))}px`;
    };
    position();
    const targets = menu.querySelectorAll<HTMLElement>('[role="menuitem"]');
    targets[firstFocus.current === 'last' ? targets.length - 1 : 0]?.focus();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
    return () => {
      window.removeEventListener('resize', position);
      window.removeEventListener('scroll', position, true);
    };
  }, [open, unavailable]);

  useLayoutEffect(() => { if (unavailable && open) close(); }, [unavailable, open]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const targets = Array.from(panel.current!.querySelectorAll<HTMLElement>('[role="menuitem"]'));
    const current = targets.indexOf(document.activeElement as HTMLElement);
    let next: number | undefined;
    if (event.key === 'ArrowDown') next = (current + 1) % targets.length;
    if (event.key === 'ArrowUp') next = (current - 1 + targets.length) % targets.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = targets.length - 1;
    if (next !== undefined) { event.preventDefault(); targets[next]?.focus(); }
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
    // Return to the trigger before the browser's normal Tab/Shift+Tab traversal.
    if (event.key === 'Tab') close();
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && event.key !== ' ') {
      const ordered = [...targets.slice(current + 1), ...targets.slice(0, current + 1)];
      const match = ordered.find(item => item.textContent?.trim().toLowerCase().startsWith(event.key.toLowerCase()));
      if (match) { event.preventDefault(); match.focus(); }
    }
  }

  return <>
    <Button ref={trigger} type="button" variant="secondary" className="min-w-11 px-3"
      aria-label={label} title={label} aria-haspopup="menu" aria-expanded={open}
      aria-controls={open ? id : undefined} disabled={unavailable}
      onClick={() => { if (open) close(); else { firstFocus.current = 'first'; setOpen(true); } }}
      onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); firstFocus.current = event.key === 'ArrowUp' ? 'last' : 'first'; setOpen(true);
        }
      }}><Icon name="overflow" /></Button>
    <div ref={panel} id={id} popover="auto" role="menu" aria-label={label} className="action-menu"
      onToggle={event => {
        if (event.newState === 'closed') {
          setOpen(false);
          // Outside clicks retain focus on their target; non-focusable targets return here.
          if (document.activeElement === document.body || panel.current?.contains(document.activeElement)) trigger.current?.focus();
        }
      }}
      onBlur={event => { if (event.relatedTarget && event.relatedTarget !== trigger.current && !event.currentTarget.contains(event.relatedTarget as Node)) close(false); }}
      onKeyDown={onKeyDown}>
      {visible.map(item => {
        const props = {
          role: 'menuitem', tabIndex: -1, 'aria-disabled': item.disabled || undefined,
          className: `action-menu-item ${item.danger ? 'text-danger' : 'text-text'}`,
          onClick: (event: MouseEvent) => {
            if (item.disabled) { event.preventDefault(); return; }
            close();
            item.onSelect?.();
          },
        };
        const content = <>{item.icon && <Icon name={item.icon} />}<span>{item.label}{item.description && <span className="mt-1 block text-label text-text-dim">{item.description}</span>}</span></>;
        return item.to !== undefined && !item.disabled
          ? <Link key={item.id} {...props} to={item.to} state={item.state}
              onKeyDown={event => { if (event.key === ' ') { event.preventDefault(); event.currentTarget.click(); } }}>{content}</Link>
          : <button key={item.id} {...props} type="button">{content}</button>;
      })}
    </div>
  </>;
}
