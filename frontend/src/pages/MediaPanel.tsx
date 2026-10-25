import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { MediaStore, mediaSlotValid, type MediaSlot } from '../catalog/MediaStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { requestJson } from '../api/http'
import { apiErrorView, type ApiErrorView } from '../api/apiError'

export function MediaPanel({ kind, target, disabled = false }: { kind: 'products' | 'stores'; target: string; disabled?: boolean }) {
  const auth = useAuthStore()
  const [store] = useState(() => new MediaStore(auth, '/api/media/' + kind + '/' + target, kind === 'products' ? 5 : 1))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => store.connect(), [store])
  return <section className="media-panel" aria-label="이미지 관리"><h2>{kind === 'products' ? '상품 이미지 (최대 5개)' : '매대 대표 이미지'}</h2>
    <p>JPEG·PNG·WebP 파일, 5MB 이하, 가로·세로 4096px 이하 및 총 1600만 픽셀 이하를 지원합니다. 애니메이션은 지원하지 않습니다. PNG로 변환한 결과가 5MB를 넘으면 저장할 수 없습니다.</p>
    <p>교체·삭제하면 이전 이미지 주소는 사용할 수 없습니다. 사진 방향은 올바르게 맞춘 뒤 올려 주세요.</p>
    {state.busy && <p role="status">이미지를 처리하고 있습니다.</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">{state.notice}</p>}
    {state.items?.map(slot => <MediaForm key={slot.slot + ':' + slot.version} item={slot} disabled={disabled || state.busy} save={(file, alt) => store.save(slot, file, alt)} />)}
    <button disabled={state.busy} onClick={() => void store.load()}>이미지 다시 확인</button>
  </section>
}
function MediaForm({ item, disabled, save }: { item: MediaSlot; disabled: boolean; save: (file: File | null, alt: string) => void }) {
  const [error, setError] = useState(''); const [confirmed, confirm] = useState(false)
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget); const file = data.get('file'); const alt = String(data.get('alt') ?? '').trim()
    if (!(file instanceof File) || !file.size || file.size > 5 * 1024 * 1024 || !/\.(jpe?g|png|webp)$/i.test(file.name) || !alt || [...alt].length > 120) { setError('허용 형식의 5MB 이하 이미지와 1~120자 설명을 입력해 주세요.'); return }
    setError(''); save(file, alt)
  }
  return <form onSubmit={submit}><fieldset disabled={disabled}><legend>이미지 {item.slot + 1}</legend>
    {item.assetId ? <img className="managed-image" src={'/api/media/' + item.assetId + '/content'} alt={item.alt} /> : <p>등록된 이미지가 없습니다.</p>}
    <label>이미지 파일 {item.slot + 1}<input name="file" type="file" accept=".jpg,.jpeg,.png,.webp" /></label>
    <label>이미지 설명 {item.slot + 1}<input name="alt" defaultValue={item.alt} /></label>
    <button type="submit">이미지 {item.slot + 1} {item.assetId ? '교체' : '업로드'}</button>
    {item.assetId && <><label className="checkbox-label"><input type="checkbox" checked={confirmed} onChange={e => confirm(e.target.checked)} />이미지 {item.slot + 1} 삭제 확인</label>
      <button type="button" disabled={!confirmed} onClick={() => save(null, '')}>이미지 {item.slot + 1} 삭제</button></>}
    {error && <p role="alert">{error}</p>}
  </fieldset></form>
}
export function PublicMedia({ kind, slug }: { kind: 'products' | 'stores'; slug: string }) {
  const [result, setResult] = useState<{ key: string; items?: MediaSlot[]; error?: ApiErrorView } | null>(null)
  const key = kind + '/' + slug
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void requestJson('/api/media/public/' + kind + '/' + encodeURIComponent(slug), { credentials: 'same-origin', cache: 'no-store', signal: controller.signal }).then(response => {
      if (!active) return
      if (response.kind === 'success' && response.status === 200 && Array.isArray(response.data) && response.data.length <= (kind === 'stores' ? 1 : 5) && response.data.every(mediaSlotValid)) setResult({ key, items: response.data })
      else setResult({ key, error: apiErrorView(response) ?? { message: '이미지를 불러오지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [key, kind, slug])
  if (result?.key !== key) return <p role="status">이미지를 확인하고 있습니다.</p>
  return <>{result.error && <ApiErrorNotice error={result.error} />}{result.items?.map(row => row.assetId && <img className="managed-image" key={row.assetId} src={'/api/media/' + row.assetId + '/content'} alt={row.alt} />)}</>
}
