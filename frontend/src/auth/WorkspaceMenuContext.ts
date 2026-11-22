import { createContext, useContext } from 'react'
import type { Workspace } from '../navigation'
import { adminFeatureAreas, type AdminPermission } from './workspacePolicy'

type WorkspaceMenu = {
  guest: boolean
  buyer: boolean
  seller: boolean
  hasSellerApplication: boolean
  permissions: readonly AdminPermission[]
  showSellerApplicationLink: () => void
}

export const WorkspaceMenuContext = createContext<WorkspaceMenu>({
  guest: false, buyer: false, seller: false, hasSellerApplication: false, permissions: [], showSellerApplicationLink: () => {},
})

export function useWorkspaceMenu() {
  return useContext(WorkspaceMenuContext)
}

export function useWorkspaceFeatures(workspace: Workspace) {
  const { permissions } = useWorkspaceMenu()
  if (workspace.path !== '/admin') return workspace.features
  return workspace.features.filter(({ path }) => {
    const area = adminFeatureAreas[path]
    // The dashboard shows a view for each granted area.
    return area === 'ADMIN' ? permissions.length > 0 : permissions.includes(area)
  })
}
