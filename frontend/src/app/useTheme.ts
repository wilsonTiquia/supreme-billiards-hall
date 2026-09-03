import { useContext, useEffect } from 'react';
import { ThemeContext, type ThemeContextValue, type Screen } from './ThemeProvider';

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useTheme must be used inside ThemeProvider');
  return context;
}

/** Declares which screen type is on show, so the theme follows it. */
export function useScreenTheme(screen: Screen): void {
  const { setScreen } = useTheme();
  useEffect(() => {
    setScreen(screen);
  }, [screen, setScreen]);
}
