import type { Line } from './CartStore'
export type Group = { slug: string; name: string; items: Line[]; subtotal: number }
export function purchaseSummary(items: Line[]): { allowed: boolean; groups: Group[]; total: number } {
  const groups = new Map<string, Group>()
  let allowed = items.length > 0, total = 0
  for (const line of items) {
    const detail = line.detail, offer = detail?.offers[0]
    if (!detail || !offer || offer.available < line.quantity) allowed = false
    if (!detail || !offer) continue
    const slug = detail.product.storeSlug
    const group = groups.get(slug) ?? { slug, name: detail.product.storeName, items: [], subtotal: 0 }
    group.items.push(line); group.subtotal += offer.price * line.quantity
    total += offer.price * line.quantity; groups.set(slug, group)
  }
  return { allowed, groups: [...groups.values()], total }
}
