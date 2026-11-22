export type DescriptionRun = { text: string; bold: boolean; italic: boolean }
export type DescriptionText = { kind: 'paragraph' | 'heading'; runs: DescriptionRun[] }
export type DescriptionImage = { kind: 'image'; assetId: string; alt: string }
export type DescriptionBlock = DescriptionText | DescriptionImage
export type DescriptionDraftBlock = DescriptionBlock | { kind: 'image'; uploadIndex: number; alt: string }
export const MAX_DESCRIPTION_BYTES = 5 * 1024 * 1024
export const MAX_DESCRIPTION_IMAGES = 5
const assetId = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
function supportedText(value: unknown): value is string {
  return typeof value === 'string' && [...value].every(char => {
    const point = char.codePointAt(0)!
    return (point >= 32 || point === 9 || point === 10) && (point < 127 || point > 159) && (point < 0xd800 || point > 0xdfff)
  })
}

export function plainDescription(blocks: DescriptionDraftBlock[]) {
  return blocks.flatMap(block => block.kind === 'image' ? [] : [block.runs.map(run => run.text).join('')]).join('\n')
}

export function descriptionValid(value: unknown): value is DescriptionBlock[] | null | undefined {
  if (value == null) return true
  if (!Array.isArray(value) || value.length > 100) return false
  let runs = 0
  const images = new Set<string>()
  for (const block of value) {
    if (!object(block) || block.uploadIndex != null) return false
    if (block.kind === 'image') {
      if (typeof block.assetId !== 'string' || !assetId.test(block.assetId) || images.has(block.assetId)
        || typeof block.alt !== 'string' || !block.alt.trim() || [...block.alt].length > 120) return false
      images.add(block.assetId)
    } else {
      if (block.kind !== 'paragraph' && block.kind !== 'heading' || !Array.isArray(block.runs)
        || !block.runs.every(run => object(run) && supportedText(run.text) && typeof run.bold === 'boolean' && typeof run.italic === 'boolean')) {
        return false
      }
      runs += block.runs.length
    }
  }
  return images.size <= MAX_DESCRIPTION_IMAGES && runs <= 256 && [...plainDescription(value)].length <= 4000
}

export function descriptionImageUrl(id: string, owner = false) {
  return owner ? '/api/media/owner/' + id + '/preview' : '/api/media/' + id + '/content'
}
