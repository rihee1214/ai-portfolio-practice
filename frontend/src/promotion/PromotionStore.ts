import { CatalogStore } from '../catalog/CatalogStore'
import type { AuthStore } from '../auth/AuthStore'
export type Promotion = { id: string; storeId: string; title: string; scope: 'PRODUCT' | 'SHIPPING'; productIds: string[]; startsAt: string; endsAt: string; kind: 'FIXED' | 'RATE' | 'FREE'; value: number; cap: number | null; state: 'DRAFT' | 'ACTIVE' | 'STOPPED' | 'ARCHIVED'; version: number; storeSlug: string; storeName: string; targets: { id: string; slug: string; title: string }[] }
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v)
export const promotionValid = (v: unknown): v is Promotion => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false
  const p = v as Record<string, unknown>
  return typeof p.storeSlug === 'string' && typeof p.storeName === 'string' && Array.isArray(p.targets) && p.targets.every(t => t && typeof t === 'object' && uuid(t.id) && typeof t.slug === 'string' && typeof t.title === 'string') && uuid(p.id) && uuid(p.storeId) && typeof p.title === 'string' && !!p.title.trim() && p.title.length <= 240
    && ['PRODUCT', 'SHIPPING'].includes(String(p.scope)) && Array.isArray(p.productIds) && p.productIds.length <= 20 && p.productIds.every(uuid)
    && typeof p.startsAt === 'string' && typeof p.endsAt === 'string' && Number.isFinite(Date.parse(p.startsAt)) && Date.parse(p.endsAt) > Date.parse(p.startsAt)
    && ['FIXED', 'RATE', 'FREE'].includes(String(p.kind)) && Number.isSafeInteger(p.value) && Number(p.value) >= 0
    && (p.cap === null || Number.isSafeInteger(p.cap) && Number(p.cap) >= 0) && Number.isSafeInteger(p.version) && Number(p.version) >= 0
    && ['DRAFT', 'ACTIVE', 'STOPPED', 'ARCHIVED'].includes(String(p.state))
}
export const koreanTime = (value: string) => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value))
export class PromotionStore extends CatalogStore<Promotion> {
  constructor(auth: AuthStore) { super(auth, '/api/seller/promotions', promotionValid) }
}
