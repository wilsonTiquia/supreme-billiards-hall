import { Icon } from './Icon';

/** Shows the theme the control switches to; the control supplies its accessible name. */
export function ThemeIcon({ to }: { to: 'light' | 'dark' }) {
  return <Icon name={to === 'light' ? 'sun' : 'moon'} />;
}
