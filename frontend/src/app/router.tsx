import { Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { HomeRedirect, RequireAdmin, RequireAuth, RequirePasswordCurrent } from './guards';
import { LoginPage } from '@/features/login/LoginPage';
import { ForcedPasswordChangePage } from '@/features/account/ForcedPasswordChangePage';
import { ChangePasswordPage } from '@/features/account/ChangePasswordPage';
import { StaffPage } from '@/features/admin/staff/StaffPage';
import { FloorPage } from '@/features/floor/FloorPage';
import { SessionPage } from '@/features/session/SessionPage';
import { CheckoutPage } from '@/features/checkout/CheckoutPage';
import { QuickSalePage } from '@/features/quicksale/QuickSalePage';
import { ReceiptPage } from '@/features/checkout/ReceiptPage';
import { EndOfDayPage } from '@/features/endofday/EndOfDayPage';
import { UnsettledPage } from '@/features/unsettled/UnsettledPage';
import { ExpensesPage } from '@/features/expenses/ExpensesPage';
import { DashboardPage } from '@/features/admin/dashboard/DashboardPage';
import { ProductsPage } from '@/features/admin/catalog/ProductsPage';
import { CategoriesPage } from '@/features/admin/catalog/CategoriesPage';
import { TablesPage } from '@/features/admin/catalog/TablesPage';
import { CustomerTypesPage } from '@/features/admin/catalog/CustomerTypesPage';
import { ExpenseCategoriesPage } from '@/features/admin/catalog/ExpenseCategoriesPage';
import { StockPage } from '@/features/admin/stock/StockPage';
import { SalesPage } from '@/features/admin/sales/SalesPage';
import { AuditPage } from '@/features/admin/audit/AuditPage';
import { SettingsPage } from '@/features/admin/settings/SettingsPage';
import { NotFoundPage } from '@/features/NotFoundPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        {/* Outside the shell and the password gate: the one screen a flagged user can reach. */}
        <Route path="change-password" element={<ForcedPasswordChangePage />} />

        <Route element={<RequirePasswordCurrent />}>
          <Route element={<AppShell />}>
            <Route index element={<HomeRedirect />} />
            <Route path="floor" element={<FloorPage />} />
            <Route path="sessions/:sessionId" element={<SessionPage />} />
            <Route path="checkout/:billId" element={<CheckoutPage />} />
            <Route path="quick-sale" element={<QuickSalePage />} />
            <Route path="receipt/:billId" element={<ReceiptPage />} />
            <Route path="end-of-day" element={<EndOfDayPage />} />
            {/* Both roles: collecting a debt is counter work, and the shape carries no cost. */}
            <Route path="unsettled" element={<UnsettledPage />} />
            <Route path="expenses" element={<ExpensesPage />} />
            <Route path="account/password" element={<ChangePasswordPage />} />

            <Route element={<RequireAdmin />}>
              <Route path="dashboard" element={<DashboardPage />} />
              <Route path="admin/products" element={<ProductsPage />} />
              <Route path="admin/categories" element={<CategoriesPage />} />
              <Route path="admin/tables" element={<TablesPage />} />
              <Route path="admin/customer-types" element={<CustomerTypesPage />} />
              <Route path="admin/expense-categories" element={<ExpenseCategoriesPage />} />
              <Route path="admin/settings" element={<SettingsPage />} />
              <Route path="admin/staff" element={<StaffPage />} />
              <Route path="admin/stock" element={<StockPage />} />
              <Route path="admin/sales" element={<SalesPage />} />
              <Route path="admin/audit" element={<AuditPage />} />
            </Route>

            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Route>
      </Route>
    </Routes>
  );
}
