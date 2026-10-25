export type Input = { skuId: string; quantity: number }
export const GUEST_KEY = 'marketplace-guest-cart'
export const JOURNAL_KEY = 'marketplace-cart-merge'
export const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v)
export function inputs(value: unknown): Input[] {
  if (!Array.isArray(value) || value.length > 100) throw new Error('장바구니 저장값을 확인할 수 없습니다.')
  const result = new Map<string, number>()
  for (const row of value) {
    if (!row || Object.keys(row).sort().join(',') !== 'quantity,skuId' || !uuid(row.skuId) || !Number.isInteger(row.quantity) || row.quantity < 1 || row.quantity > 99) throw new Error('장바구니 저장값을 확인할 수 없습니다.')
    const id = row.skuId.toLowerCase(); result.set(id, Math.min(99, (result.get(id) ?? 0) + row.quantity))
  }
  return [...result.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([skuId, quantity]) => ({ skuId, quantity }))
}
type Journal = { requestId: string; items: Input[] }
export class GuestCart {
  constructor(private storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>) { }
  read(): Input[] { return inputs(JSON.parse(this.storage.getItem(GUEST_KEY) ?? '[]')) }
  set(sku: string, quantity: number) {
    if (!uuid(sku) || !Number.isInteger(quantity) || quantity < 0 || quantity > 99) throw new Error('수량은 1~99개로 입력해 주세요.')
    if (this.storage.getItem(JOURNAL_KEY)) throw new Error('방문자 장바구니 가져오기를 먼저 완료하거나 취소해 주세요.')
    const rows = this.read().filter(row => row.skuId !== sku.toLowerCase())
    if (quantity) rows.push({ skuId: sku.toLowerCase(), quantity })
    if (rows.length > 100) throw new Error('장바구니는 최대 100종까지 담을 수 있습니다.')
    this.storage.setItem(GUEST_KEY, JSON.stringify(inputs(rows)))
  }
  prepare(): Journal | null {
    const saved = this.storage.getItem(JOURNAL_KEY)
    if (saved) {
      const value = JSON.parse(saved)
      if (!value || Object.keys(value).sort().join(',') !== 'items,requestId' || !uuid(value.requestId)) throw new Error('가져오기 복구 기록을 확인할 수 없습니다.')
      return { requestId: value.requestId, items: inputs(value.items) }
    }
    const items = this.read(); if (!items.length) return null
    const journal = { requestId: crypto.randomUUID(), items }; this.storage.setItem(JOURNAL_KEY, JSON.stringify(journal)); return journal
  }
  acknowledge(requestId: string) {
    const saved = this.storage.getItem(JOURNAL_KEY)
    if (!saved) throw new Error('가져오기 복구 기록이 없습니다.')
    const journal = this.prepare()!
    if (journal.requestId !== requestId) throw new Error('가져오기 기록이 변경되었습니다.')
    const current = this.read()
    if (current.length && JSON.stringify(current) !== JSON.stringify(journal.items)) throw new Error('방문자 품목이 변경되었습니다. 확인 후 다시 시도해 주세요.')
    this.storage.removeItem(GUEST_KEY); this.storage.removeItem(JOURNAL_KEY)
  }
  clear() { this.storage.removeItem(GUEST_KEY); this.storage.removeItem(JOURNAL_KEY) }
}
