import { requestJson } from '../api/http'

export const adminPermissions = ['ACCOUNTS', 'CONTENT', 'SUPPORT', 'SETTLEMENTS', 'SYSTEM'] as const
export type AdminPermission = (typeof adminPermissions)[number]
export type AccessArea = 'SELLER' | 'ADMIN' | AdminPermission

export const adminFeatureAreas: Record<string, 'ADMIN' | AdminPermission> = {
  dashboard: 'ADMIN',
  publications: 'CONTENT',
  settings: 'SYSTEM',
  jobs: 'SYSTEM',
  users: 'ACCOUNTS',
  sellers: 'ACCOUNTS',
  stores: 'ACCOUNTS',
  catalog: 'CONTENT',
  moderation: 'CONTENT',
  orders: 'SUPPORT',
  refunds: 'SYSTEM',
  notifications: 'SYSTEM',
  settlements: 'SETTLEMENTS',
  support: 'SUPPORT',
  reports: 'CONTENT',
}

export async function checkWorkspaceAccess(area: AccessArea, signal: AbortSignal) {
  const path = area === 'SELLER' ? '/api/seller/access'
    : area === 'ADMIN' ? '/api/admin/access' : '/api/admin/' + area.toLowerCase() + '/access'
  const response = await requestJson(path, { credentials: 'same-origin', cache: 'no-store', signal })
  const allowed = response.kind === 'success' && response.status === 200 && typeof response.data === 'object'
    && response.data !== null && 'access' in response.data && response.data.access === area
  return { response, allowed }
}
