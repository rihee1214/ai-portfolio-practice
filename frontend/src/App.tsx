import { BrowserRouter, Route, Routes } from 'react-router'
import { SiteLayout } from './layouts/SiteLayout'
import { WorkspaceLayout } from './layouts/WorkspaceLayout'
import { workspaces } from './navigation'
import HomePage from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { FeaturePage, WorkspaceOverview } from './pages/WorkspacePages'
import ApiErrorPreview from './pages/ApiErrorPreview'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<SiteLayout />}>
          <Route index element={<HomePage />} />
          {import.meta.env.DEV && <Route path="/dev/api-errors" element={<ApiErrorPreview />} />}
          {workspaces.map((workspace) => (
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
