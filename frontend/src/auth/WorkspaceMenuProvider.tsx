import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { requestJson } from '../api/http'
import { readSellerApplicationsPage } from '../seller/SellerApplicationsStore'
import { useAuthStore } from './AuthContext'
import type { AuthState } from './AuthStore'
import { WorkspaceMenuContext } from './WorkspaceMenuContext'
import { adminPermissions, checkWorkspaceAccess, type AdminPermission } from './workspacePolicy'

export default function WorkspaceMenuProvider({ children }: { children: ReactNode }) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const checked = session.checked && !session.busy && !session.error
  const member = checked ? session.member : null
  const buyer = member?.roles.includes('BUYER') ?? false
  const seller = member?.roles.includes('SELLER') ?? false
  const admin = member?.roles.includes('ADMIN') ?? false
  const [sellerAccess, setSellerAccess] = useState<AuthState>()
  const [adminAccess, setAdminAccess] = useState<{ session: AuthState; permissions: AdminPermission[] }>()
  const [application, setApplication] = useState<AuthState>()

  useEffect(() => {
    if (!seller && !admin) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10000)])
    if (seller) void checkWorkspaceAccess('SELLER', signal).then(({ allowed }) => {
      if (allowed && !signal.aborted) setSellerAccess(session)
    })
    if (admin) void Promise.all(adminPermissions.map(async area => {
      const { allowed } = await checkWorkspaceAccess(area, signal)
      return allowed ? area : null
    })).then(results => {
      if (!controller.signal.aborted) {
        setAdminAccess({ session, permissions: results.filter(area => area !== null) })
      }
    })
    return () => controller.abort()
  }, [admin, seller, session])

  useEffect(() => {
    if (!buyer || seller || !member) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10000)])
    void requestJson('/api/seller/applications?page=0', { credentials: 'same-origin', cache: 'no-store', signal }).then(response => {
      const page = response.kind === 'success' && response.status === 200 ? readSellerApplicationsPage(response.data, 0) : null
      if (!signal.aborted && page?.items.some(item => item.applicantId === member.id)) setApplication(session)
    })
    return () => controller.abort()
  }, [buyer, member, seller, session])

  return <WorkspaceMenuContext value={{
    guest: checked && !member,
    buyer,
    seller: sellerAccess === session,
    hasSellerApplication: buyer && !seller && application === session,
    permissions: adminAccess?.session === session ? adminAccess.permissions : [],
    showSellerApplicationLink: () => setApplication(session),
  }}>{children}</WorkspaceMenuContext>
}
