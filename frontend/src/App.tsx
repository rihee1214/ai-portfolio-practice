import { BrowserRouter, Route, Routes } from 'react-router'
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

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<SiteLayout />}>
          <Route index element={<HomePage />} />
          <Route element={<AuthLayout />}>
            <Route path="/password/request" element={<PasswordPage key="request" mode="request" />} />
            <Route path="/password/reset" element={<PasswordPage key="reset" mode="reset" />} />
            <Route path="/signup" element={<AuthForm key="signup" mode="signup" />} />
            <Route path="/verify-email" element={<AuthForm key="verify" mode="verify-email" />} />
            <Route path="/login" element={<AuthForm key="login" mode="login" />} />
            <Route path="/account">
              <Route index element={<AccountPage />} />
              <Route path="profile" element={<AccountDetailsPage key="profile" resource="profile" />} />
              <Route path="addresses" element={<AccountDetailsPage key="addresses" resource="addresses" />} />
              <Route element={<WorkspaceLayout workspace={workspaces[0]} />}>
                {workspaces[0].features.filter(feature => feature.path !== 'addresses').map(feature => <Route key={feature.path} path={feature.path} element={<FeaturePage feature={feature} />} />)}
              </Route>
            </Route>
          </Route>
          {import.meta.env.DEV && <Route path="/dev/api-errors" element={<ApiErrorPreview />} />}
          {workspaces.filter(workspace => workspace.path !== '/account').map((workspace) => (
            <Route key={workspace.path} path={workspace.path} element={<WorkspaceLayout workspace={workspace} />}>
              <Route index element={<WorkspaceOverview workspace={workspace} />} />
              {workspace.features.map((feature) => (
                <Route key={feature.path} path={feature.path} element={<FeaturePage feature={feature} />} />
              ))}
            </Route>
          ))}
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
