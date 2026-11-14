import { SellerSettlementPage } from './settlement/SellerSettlementPage'
import { RetryNotificationsPage } from './notification/RetryNotificationsPage'
import { NotificationPage } from './notification/NotificationPage'
import { OwnerDecisionPage } from './notification/OwnerDecisionPage'
import { SettlementPage } from './settlement/SettlementPage'
import { ManagedAccountsPage } from './pages/ManagedAccountsPage'
import { ModerationPage } from './moderation/ModerationPage'
import { MyReports } from './reports/ProductReport'
import { ReportQueue } from './reports/ReportQueue'
import { LostAccessForm, SupportDesk } from './support/SupportDesk'
import { SellerQuestions } from './content/SellerQuestions'
import { SystemRefundsPage } from './payment/SystemRefundsPage'
import { ReturnSupportPage } from './returns/ReturnSupportPage'
import { PaymentPage } from './payment/PaymentPage'
import { OrdersPage } from './pages/OrdersPage'
import { OrderDetailPage } from './pages/OrderDetailPage'
import { SellerShipmentsPage } from './pages/SellerShipmentsPage'
import { CouponPage, CouponTemplatePage } from './pages/CouponPages'
import { PromotionPage, PublicPromotionPage } from './pages/PromotionPages'
import { CheckoutPage } from './pages/PurchasePages'
import { GuestPage } from './pages/GuestPage'
import { GuestRecoveryPage } from './pages/GuestRecoveryPage'
import { GuestOrdersPage } from './pages/GuestOrdersPage'
import { WishlistPage } from './pages/WishlistPages'
import AuthProvider from './auth/AuthProvider'
import CartProvider from './cart/CartProvider'
import { CartPage } from './pages/CartPages'
import { BrowsePage, ProductDetailPage } from './pages/BrowsePages'
import { BrowserRouter, Route, Routes, useLocation, matchPath, Outlet } from 'react-router'
import { SiteLayout } from './layouts/SiteLayout'
import { WorkspaceLayout } from './layouts/WorkspaceLayout'
import { workspaces } from './navigation'
import HomePage from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { FeaturePage, WorkspaceOverview } from './pages/WorkspacePages'
import ApiErrorPreview from './pages/ApiErrorPreview'
import AuthLayout from './auth/AuthLayout'
import { AccountPage, AuthForm } from './pages/AuthPages'
import { AccountDetailsPage } from './pages/AccountPages'
import { PasswordPage } from './pages/PasswordPages'
import { WorkspaceAccess, type AccessArea } from './auth/WorkspaceAccess'
import type { Workspace } from './navigation'
import { SellerApplicationsPage } from './pages/SellerApplicationsPage'
import { StoreEditorPage, PublicStorePage } from './pages/StorePages'
import { StoreOperationsPage, SellerFeaturePage } from './pages/StoreLifecycle'
import { CategoryPage } from './pages/CatalogPages'

function ProtectedWorkspace({ workspace }: { workspace: Workspace }) {
  const location = useLocation()
  const feature = workspace.features.find(item => matchPath({ path: workspace.path + '/' + item.path, end: true }, location.pathname))
  const areas: Record<string, AccessArea> = { users: 'ACCOUNTS', sellers: 'ACCOUNTS', stores: 'ACCOUNTS', catalog: 'CONTENT', moderation: 'CONTENT', orders: 'SUPPORT', refunds: 'SYSTEM', notifications: 'SYSTEM', settlements: 'SETTLEMENTS', support: 'SUPPORT', reports: 'CONTENT' }
  const area: AccessArea = workspace.path === '/seller' ? 'SELLER' : feature ? areas[feature.path] : 'ADMIN'
  return <WorkspaceAccess key={location.pathname} area={area} title={feature?.title ?? workspace.title}>
    <WorkspaceLayout workspace={workspace} />
  </WorkspaceAccess>
}

function ShoppingProviders() { return <AuthProvider><CartProvider><Outlet /></CartProvider></AuthProvider> }

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<SiteLayout />}><Route element={<ShoppingProviders />}>
          <Route path="/payments/:number" element={<PaymentPage />} /><Route path="/payments/:number/success" element={<PaymentPage />} /><Route path="/payments/:number/failure" element={<PaymentPage />} /><Route path="/checkout/guest" element={<GuestPage />} />
          <Route path="/support" element={<SupportDesk />} /><Route path="/support/lost-access" element={<LostAccessForm />} />
          <Route path="/guest/recovery" element={<GuestRecoveryPage />} /><Route path="/guest/security" element={<GuestRecoveryPage manage />} />
          <Route path="/guest/orders/login" element={<GuestOrdersPage />} /><Route path="/guest/orders" element={<GuestOrdersPage />} /><Route path="/guest/orders/:number" element={<OrderDetailPage guest />} />
          <Route path="/events" element={<PublicPromotionPage />} /><Route path="/events/:id" element={<PublicPromotionPage />} /><Route index element={<HomePage />} /><Route path="/cart" element={<CartPage />} /><Route path="/checkout" element={<CheckoutPage />} /><Route path="/products" element={<BrowsePage />} /><Route path="/products/:slug" element={<ProductDetailPage />} />
          <Route path="/stores/:slug" element={<PublicStorePage />} />
          <Route element={<AuthLayout />}>
            <Route path="/password/request" element={<PasswordPage key="request" mode="request" />} />
            <Route path="/password/reset" element={<PasswordPage key="reset" mode="reset" />} />
            <Route path="/signup" element={<AuthForm key="signup" mode="signup" />} />
            <Route path="/verify-email" element={<AuthForm key="verify" mode="verify-email" />} />
            <Route path="/login" element={<AuthForm key="login" mode="login" />} />
            <Route path="/account">
              <Route path="notifications" element={<NotificationPage />} />
              <Route path="moderation/:id" element={<OwnerDecisionPage />} />
              <Route path="orders" element={<OrdersPage />} /><Route path="orders/:number" element={<OrderDetailPage />} />
              <Route path="reports" element={<MyReports />} /><Route path="coupons" element={<CouponPage />} /><Route path="wishlist" element={<WishlistPage />} /><Route path="seller-application" element={<SellerApplicationsPage />} />
              <Route index element={<AccountPage />} />
              <Route path="profile" element={<AccountDetailsPage key="profile" resource="profile" />} />
              <Route path="addresses" element={<AccountDetailsPage key="addresses" resource="addresses" />} />
              <Route element={<WorkspaceLayout workspace={workspaces[0]} />}>
                {workspaces[0].features.filter(feature => !['addresses','wishlist','coupons','orders','reports'].includes(feature.path)).map(feature => <Route key={feature.path} path={feature.path} element={<FeaturePage feature={feature} />} />)}
              </Route>
            </Route>
          {workspaces.filter(workspace => workspace.path !== '/account').map((workspace) => (
            <Route key={workspace.path} path={workspace.path} element={<ProtectedWorkspace workspace={workspace} />}>
              <Route index element={<WorkspaceOverview workspace={workspace} />} />
              {workspace.path === '/seller' && <Route path="orders/:id" element={<SellerShipmentsPage />} />}
              {workspace.features.map((feature) => (
                <Route key={feature.path} path={feature.path} element={workspace.path === '/seller' ? feature.path === 'settlements' ? <SellerSettlementPage /> : feature.path === 'questions' ? <SellerQuestions /> : feature.path === 'orders' ? <SellerShipmentsPage /> : feature.path === 'coupons' ? <CouponTemplatePage /> : feature.path === 'promotions' ? <PromotionPage /> : feature.path === 'store' ? <StoreEditorPage /> : <SellerFeaturePage feature={feature} /> : feature.path === 'settlements' ? <SettlementPage /> : feature.path === 'users' ? <ManagedAccountsPage /> : feature.path === 'moderation' ? <ModerationPage /> : feature.path === 'reports' ? <ReportQueue /> : feature.path === 'support' ? <SupportDesk reader="OPERATOR" /> : feature.path === 'notifications' ? <RetryNotificationsPage /> : feature.path === 'refunds' ? <SystemRefundsPage /> : feature.path === 'orders' ? <ReturnSupportPage /> : feature.path === 'catalog' ? <CategoryPage /> : feature.path === 'stores' ? <StoreOperationsPage /> : feature.path === 'sellers' ? <SellerApplicationsPage admin /> : <FeaturePage feature={feature} />} />
              ))}
            </Route>
          ))}
          </Route>
          </Route>
          {import.meta.env.DEV && <Route path="/dev/api-errors" element={<ApiErrorPreview />} />}
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
