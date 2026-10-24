import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link, useParams } from 'react-router'
import { useAuthStore } from '../auth/AuthContext'
import { StoreEditorStore, publicStore, storeStates, type StoreFields } from '../seller/StoreEditorStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { apiErrorView, type ApiErrorView } from '../api/apiError'
import { requestJson } from '../api/http'
import { StoreHistory } from './StoreLifecycle'

const fields: [keyof StoreFields, string, number][] = [['slug', '매대 URL 주소', 63], ['name', '매대 이름', 80], ['introduction', '매대 소개', 1000], ['address', '공개용 주소', 200], ['notice', '매대 공지', 1000], ['imageKey', '대표 이미지 키', 100], ['imageAlt', '이미지 대체 텍스트', 120]]
export function StoreEditorPage() {
  const auth = useAuthStore()
  const [store] = useState(() => new StoreEditorStore(auth))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [invalid, setInvalid] = useState('')
  useEffect(() => store.connect(), [store])
  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const value = Object.fromEntries(fields.map(([key]) => [key, String(data.get(key) ?? '').trim()])) as StoreFields
    if (fields.some(([key, , max]) => [...value[key]].length > max) || !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(value.slug)
      || !value.name || !value.introduction || !value.address || Boolean(value.imageKey) !== Boolean(value.imageAlt)
      || (value.imageKey && !/^[A-Za-z0-9_-]+$/.test(value.imageKey))) { setInvalid('주소 형식·입력 길이·이미지 키와 대체 텍스트를 확인해 주세요.'); return }
    setInvalid(''); void store.save({ ...value, visible: data.get('visible') === 'on' })
  }
  return <section className="auth-card store-card"><h1>내 매대</h1>
    <p>포트폴리오용 가상 정보만 입력하세요. 공개용 주소는 매대 공개 시 방문자에게 표시됩니다.</p>
    <p>URL 주소는 소문자 영문·숫자·내부 하이픈 3~63자이며 생성 후 변경할 수 없습니다.</p>
    <p>대표 이미지는 키와 대체 텍스트만 저장합니다. 파일 업로드와 이미지 표시는 아직 제공하지 않습니다.</p>
    {state.busy && <p role="status">매대 정보를 확인하고 있습니다.</p>}
    {state.store && <p>운영 상태: <strong>{storeStates[state.store.state]}</strong></p>}
    {state.store !== undefined && (!state.store || state.store.state === 'OPEN') && <form key={state.store ? state.store.id + ':' + state.store.version : 'new'} onSubmit={submit}>
      {fields.map(([key, label, max]) => <div key={key}><label htmlFor={'store-' + key}>{label}</label>
        <input id={'store-' + key} name={key} defaultValue={state.store?.[key] ?? ''} maxLength={max * 2}
          required={['slug', 'name', 'introduction', 'address'].includes(key)} readOnly={key === 'slug' && Boolean(state.store)} disabled={state.busy} /></div>)}
      <label className="checkbox-label"><input name="visible" type="checkbox" defaultChecked={state.store?.visible ?? false} disabled={state.busy} />매대 공개</label>
      <button disabled={state.busy}>{state.store ? '매대 저장' : '매대 만들기'}</button>
    </form>}
    {state.store && <p><Link to={'/stores/' + state.store.slug}>공개 매대 확인</Link> · {state.store.visible ? '공개' : '비공개'}</p>}
    {state.store && state.store.state !== 'OPEN' && <p>편집과 신규 판매는 제한됩니다. 구매와 기존 거래 대응 공간은 계속 이용할 수 있습니다.</p>}
    {state.store && state.store.state !== 'CLOSED' && <form onSubmit={event => {
      event.preventDefault(); const data = new FormData(event.currentTarget); const reason = String(data.get('reason') ?? '').trim()
      if (!reason || [...reason].length > 300 || data.get('confirm') !== 'on') { setInvalid('폐점 사유와 복구 불가 확인이 필요합니다.'); return }
      setInvalid(''); void store.close(reason)
    }}><h2>매대 폐점</h2><p>폐점은 되돌릴 수 없으며 새 매대를 만들 수 없습니다. 기존 데이터는 유지됩니다.</p>
      <label htmlFor="close-reason">폐점 사유</label><input id="close-reason" name="reason" required maxLength={600} disabled={state.busy} />
      <label className="checkbox-label"><input type="checkbox" name="confirm" required disabled={state.busy} />복구할 수 없음을 확인했습니다</label>
      <button disabled={state.busy}>매대 폐점하기</button></form>}
    {state.store && <StoreHistory id={state.store.id} version={state.store.version} />}
    <button disabled={state.busy} onClick={() => { setInvalid(''); void store.load() }}>매대 다시 불러오기</button>
    <p>다른 화면에서 수정했거나 결과가 불확실하면 다시 불러온 후 저장하세요.</p>
    {invalid && <p role="alert">{invalid}</p>}{state.error && <ApiErrorNotice error={state.error} />}{state.notice && <p role="status">{state.notice}</p>}
  </section>
}

export function PublicStorePage() {
  const { slug } = useParams()
  const [attempt, retry] = useState(0)
  const [state, setState] = useState<{ slug?: string; attempt: number; store?: StoreFields; error?: ApiErrorView }>({ attempt: -1 })
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void requestJson('/api/stores/' + encodeURIComponent(slug ?? ''), { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) }).then(result => {
      if (!active) return
      if (result.kind === 'success' && result.status === 200 && publicStore(result.data)) setState({ slug, attempt, store: result.data })
      else setState({ slug, attempt, error: apiErrorView(result) ?? { message: '매대 정보를 확인하지 못했습니다.' } })
    })
    return () => { active = false; controller.abort() }
  }, [slug, attempt])
  const current = state.slug === slug && state.attempt === attempt
  return <section className="auth-card store-card"><h1>매대 소개</h1>
    {!current ? <p role="status">매대 정보를 확인하고 있습니다.</p> : state.store ? <>
      <h2>{state.store.name}</h2><p>{state.store.introduction}</p><h3>공개용 주소</h3><p>{state.store.address}</p>
      <h3>공지</h3><p>{state.store.notice || '등록된 공지가 없습니다.'}</p>
      {state.store.imageKey && <p>대표 이미지 메타데이터 등록됨 · {state.store.imageAlt} (이미지 표시 준비 중)</p>}
      <p>상품 목록은 준비 중입니다.</p>
    </> : state.error && <ApiErrorNotice error={state.error} />}
    <button onClick={() => retry(value => value + 1)}>매대 다시 확인</button><p><Link to="/">홈으로</Link></p>
  </section>
}
