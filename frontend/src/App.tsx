import { BrowserRouter, Route, Routes, useLocation, matchPath } from 'react-router'
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

function ProtectedWorkspace({ workspace }: { workspace: Workspace }) {
  const location = useLocation()
  const feature = workspace.features.find(item => matchPath({ path: workspace.path + '/' + item.path, end: true }, location.pathname))
  const areas: Record<string, AccessArea> = { users: 'ACCOUNTS', sellers: 'ACCOUNTS', stores: 'ACCOUNTS', moderation: 'CONTENT', orders: 'SUPPORT' }
  const area: AccessArea = workspace.path === '/seller' ? 'SELLER' : feature ? areas[feature.path] : 'ADMIN'
  return <WorkspaceAccess key={location.pathname} area={area} title={feature?.title ?? workspace.title}>
    <WorkspaceLayout workspace={workspace} />
  </WorkspaceAccess>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<SiteLayout />}>
          <Route index element={<HomePage />} />
          <Route path="/stores/:slug" element={<PublicStorePage />} />
          <Route element={<AuthLayout />}>
            <Route path="/password/request" element={<PasswordPage key="request" mode="request" />} />
            <Route path="/password/reset" element={<PasswordPage key="reset" mode="reset" />} />
            <Route path="/signup" element={<AuthForm key="signup" mode="signup" />} />
            <Route path="/verify-email" element={<AuthForm key="verify" mode="verify-email" />} />
            <Route path="/login" element={<AuthForm key="login" mode="login" />} />
            <Route path="/account">
              <Route path="seller-application" element={<SellerApplicationsPage />} />
              <Route index element={<AccountPage />} />
              <Route path="profile" element={<AccountDetailsPage key="profile" resource="profile" />} />
              <Route path="addresses" element={<AccountDetailsPage key="addresses" resource="addresses" />} />
              <Route element={<WorkspaceLayout workspace={workspaces[0]} />}>
                {workspaces[0].features.filter(feature => feature.path !== 'addresses').map(feature => <Route key={feature.path} path={feature.path} element={<FeaturePage feature={feature} />} />)}
              </Route>
            </Route>
          {workspaces.filter(workspace => workspace.path !== '/account').map((workspace) => (
            <Route key={workspace.path} path={workspace.path} element={<ProtectedWorkspace workspace={workspace} />}>
              <Route index element={<WorkspaceOverview workspace={workspace} />} />
              {workspace.features.map((feature) => (
                <Route key={feature.path} path={feature.path} element={workspace.path === '/seller' ? feature.path === 'store' ? <StoreEditorPage /> : <SellerFeaturePage feature={feature} /> : feature.path === 'stores' ? <StoreOperationsPage /> : feature.path === 'sellers' ? <SellerApplicationsPage admin /> : <FeaturePage feature={feature} />} />
              ))}
            </Route>
          ))}
          </Route>
          {import.meta.env.DEV && <Route path="/dev/api-errors" element={<ApiErrorPreview />} />}
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
