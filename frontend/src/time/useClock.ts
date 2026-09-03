import { useContext } from 'react';
import { ClockContext, type ClockContextValue } from './ClockProvider';

export function useClock(): ClockContextValue {
  const context = useContext(ClockContext);
  if (!context) throw new Error('useClock must be used inside ClockProvider');
  return context;
}
