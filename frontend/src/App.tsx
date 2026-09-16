import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { queryClient } from './app/queryClient';
import { AppRoutes } from './app/router';
import { AuthProvider } from './auth/AuthProvider';
import { ClockProvider } from './time/ClockProvider';
import { ThemeProvider } from './app/ThemeProvider';
import { ToastProvider } from './components/Toast';
import { ErrorBoundary } from './components/ErrorBoundary';

/* Provider order matters: AuthProvider needs the router for its 401 redirect and the query
   client for the cache it clears, and ClockProvider waits on auth before asking for the
   server time. */
export function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <ClockProvider>
              <ThemeProvider>
                <ToastProvider>
                  <AppRoutes />
                </ToastProvider>
              </ThemeProvider>
            </ClockProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
