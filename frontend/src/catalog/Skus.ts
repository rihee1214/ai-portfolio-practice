export type SkuOption = { name: string; value: string }
export type Sku = { id: string; code: string; options: SkuOption[]; price: number; active: boolean; version: number }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
export const invalidOptionText = (v: string) => [...v].some(char => { const c = char.codePointAt(0)!; return c < 32 || (c >= 127 && c <= 159) || (c >= 0xd800 && c <= 0xdfff) })
const label = (v: unknown): v is string => typeof v === 'string' && !!v.trim() && [...v].length <= 40 && !invalidOptionText(v)
export const skuValid = (v: unknown): v is Sku => object(v) && typeof v.id === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v.id)
  && v.code === 'sku-' + v.id && Number.isSafeInteger(v.version) && Number(v.version) >= 0
  && Number.isSafeInteger(v.price) && Number(v.price) >= 0 && Number(v.price) <= 999999999 && typeof v.active === 'boolean'
  && Array.isArray(v.options) && v.options.length <= 3 && v.options.every(o => object(o) && label(o.name) && label(o.value))
  && new Set(v.options.map(o => o.name)).size === v.options.length
export const skuLabel = (sku: Sku) => sku.options.length ? sku.options.map(o => o.name + ': ' + o.value).join(' / ') : '기본 SKU'
