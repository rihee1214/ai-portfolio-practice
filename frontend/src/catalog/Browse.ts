import {returnPolicyValid,type ReturnPolicy} from './ReturnPolicy'
import type { JsonResult } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { descriptionValid, type DescriptionBlock } from './ProductDescription'
export type ProductThumbnail = { assetId: string; alt: string }
export type Item = { slug: string; title: string; description: string; categoryId: string; categoryName: string; storeSlug: string; storeName: string; minPrice: number | null; soldOut: boolean; returnPolicy: ReturnPolicy; thumbnail?: ProductThumbnail | null }
export type Page = { items: Item[]; page: number; hasMore: boolean }
export type Detail = { product: Item; descriptionBlocks?: DescriptionBlock[] | null; offers: { id: string; options: { name: string; value: string }[]; price: number; available: number }[] }
export type Query = { q: string; category: string; store: string; minPrice: string; maxPrice: string; inStock: boolean; sort: string; page: number }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const slugValid = (v: unknown): v is string => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v)
const number = (v: unknown, max = 999999999): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= max
const text = (v: unknown, max: number): v is string => typeof v === 'string' && [...v].length <= max
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
export function parseQuery(search: string): Query | null {
  const p = new URLSearchParams(search)
  for (const key of ['q', 'category', 'store', 'minPrice', 'maxPrice', 'inStock', 'sort', 'page']) if (p.getAll(key).length > 1) return null
  const q = (p.get('q') ?? '').normalize('NFC'), category = p.get('category') ?? '', store = p.get('store') ?? ''
  const minPrice = p.get('minPrice') ?? '', maxPrice = p.get('maxPrice') ?? '', page = p.get('page') ?? '0', sort = p.get('sort') ?? 'newest', stock = p.get('inStock') ?? 'false'
  if ([...q].length > 80 || [...q].some(c => c.codePointAt(0)! < 32 || (c.codePointAt(0)! >= 127 && c.codePointAt(0)! <= 159)) || category && !uuid.test(category) || store && !slugValid(store)
    || !/^\d+$/.test(page) || !number(Number(page), 10000) || !['newest', 'price_asc', 'price_desc', 'name'].includes(sort) || !['true', 'false'].includes(stock)
    || [minPrice, maxPrice].some(v => v !== '' && (!/^\d+$/.test(v) || !number(Number(v)))) || minPrice !== '' && maxPrice !== '' && Number(minPrice) > Number(maxPrice)) return null
  return { q: q.trim(), category, store, minPrice, maxPrice, inStock: stock === 'true', sort, page: Number(page) }
}
export function queryString(query: Query): string {
  const p = new URLSearchParams()
  for (const key of ['q', 'category', 'store', 'minPrice', 'maxPrice'] as const) if (query[key] !== '') p.set(key, query[key])
  if (query.inStock) p.set('inStock', 'true')
  p.set('sort', query.sort); p.set('page', String(query.page)); return p.toString()
}
function itemValid(v: unknown): v is Item {
  return record(v) && slugValid(v.slug) && text(v.title, 120) && v.title.trim() !== '' && text(v.description, 4000) && typeof v.categoryId === 'string' && uuid.test(v.categoryId)
    && text(v.categoryName, 80) && slugValid(v.storeSlug) && text(v.storeName, 80) && (v.minPrice === null || number(v.minPrice)) && typeof v.soldOut === 'boolean' && returnPolicyValid(v.returnPolicy)
    && (v.thumbnail == null || record(v.thumbnail) && typeof v.thumbnail.assetId === 'string' && uuid.test(v.thumbnail.assetId)
      && text(v.thumbnail.alt, 120) && Boolean(v.thumbnail.alt.trim()))
}
export function pageValid(v: unknown): v is Page { return record(v) && Array.isArray(v.items) && v.items.length <= 20 && v.items.every(itemValid) && number(v.page, 10000) && typeof v.hasMore === 'boolean' }
export function detailValid(v: unknown): v is Detail {
  return record(v) && itemValid(v.product) && descriptionValid(v.descriptionBlocks) && Array.isArray(v.offers) && v.offers.length <= 100 && v.offers.every(o => record(o) && typeof o.id === 'string' && uuid.test(o.id)
    && number(o.price) && number(o.available) && Array.isArray(o.options) && o.options.length <= 3 && o.options.every(x => record(x) && text(x.name, 40) && text(x.value, 40)))
}
type State<T> = { key: string; busy: boolean; data?: T; error?: ApiErrorView }
export class BrowseResource<T> {
  private state: State<T> = { key: '', busy: false }
  private listeners = new Set<() => void>()
  private generation = 0
  private controller?: AbortController
  constructor(private validate: (data: unknown) => data is T, private request: (path: string, init?: RequestInit) => Promise<JsonResult>) { }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit(state: State<T>) { this.state = state; this.listeners.forEach(fn => fn()) }
  load = async (key: string) => {
    const generation = ++this.generation; this.controller?.abort(); const controller = this.controller = new AbortController()
    this.emit({ key, busy: true })
    const result = await this.request(key, { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) })
    if (generation !== this.generation || controller.signal.aborted) return
    if (result.kind === 'success' && result.status === 200 && this.validate(result.data)) this.emit({ key, busy: false, data: result.data })
    else this.emit({ key, busy: false, error: apiErrorView(result) ?? { message: '상품 정보를 확인하지 못했습니다. 다시 시도해 주세요.' } })
  }
  stop = () => { ++this.generation; this.controller?.abort() }
}
