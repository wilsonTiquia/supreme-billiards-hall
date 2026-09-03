import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';

/**
 * Theme follows the screen, not the person.
 *
 * The floor is worked all night in a dim room and is always dark; the dashboard is read in
 * daylight and is always light. An employee inheriting the light theme because the owner
 * signed in earlier is exactly the wrong outcome, so the override is remembered per screen
 * type rather than as one global preference.
 */
export type Screen = 'pos' | 'admin';
export type Theme = 'dark' | 'light';

const DEFAULTS: Record<Screen, Theme> = { pos: 'dark', admin: 'light' };
const storageKey = (screen: Screen) => `supreme.theme.${screen}`;

export interface ThemeContextValue {
  screen: Screen;
  theme: Theme;
  setScreen: (screen: Screen) => void;
  toggle: () => void;
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);

function readOverride(screen: Screen): Theme | null {
  try {
    const stored = localStorage.getItem(storageKey(screen));
    return stored === 'dark' || stored === 'light' ? stored : null;
  } catch {
    // Private windows and locked-down browsers throw on access rather than returning null.
    return null;
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [screen, setScreen] = useState<Screen>('pos');
  const [overrides, setOverrides] = useState<Partial<Record<Screen, Theme>>>(() => ({
    pos: readOverride('pos') ?? undefined,
    admin: readOverride('admin') ?? undefined,
  }));

  const theme = overrides[screen] ?? DEFAULTS[screen];

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const toggle = useCallback(() => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    setOverrides((current) => ({ ...current, [screen]: next }));
    try {
      localStorage.setItem(storageKey(screen), next);
    } catch {
      /* a lost preference is not worth breaking the screen over */
    }
  }, [screen, theme]);

  const value = useMemo<ThemeContextValue>(
    () => ({ screen, theme, setScreen, toggle }),
    [screen, theme, toggle],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
