import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { Link } from 'react-router'
import { useAuthStore } from '../auth/AuthContext'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
import { SellerApplicationsStore } from '../seller/SellerApplicationsStore'

const labels: Record<string, string> = { PENDING: '심사 대기', APPROVED: '승인', REJECTED: '반려' }
export function SellerApplicationsPage({ admin = false }: { admin?: boolean }) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const [store] = useState(() => new SellerApplicationsStore(auth, admin))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [validation, setValidation] = useState('')
  useEffect(() => {
    const disconnect = store.connect()
    // AccountPage clears its session on exit; the admin workspace already refreshes it.
    if (!admin) auth.invalidate()
    return disconnect
  }, [store, auth, admin])
  const ready = session.member && session.checked && !session.busy
  async function submit(event: SubmitEvent<HTMLFormElement>, id?: string) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const fields: Record<string, string> = admin ? { decision: String(data.get('decision')), reason: String(data.get('reason') ?? '').trim() }
      : { displayName: String(data.get('displayName') ?? '').trim(), description: String(data.get('description') ?? '').trim() }
    if (Object.entries(fields).some(([key, value]) => !value || [...value].length > (key === 'description' ? 500 : key === 'displayName' ? 80 : 300))) {
      setValidation('입력 내용과 최대 길이를 확인해 주세요.'); return
    }
    setValidation('')
    if (await store.save(fields, id)) form.reset()
  }
  return <section className="auth-card"><h1>{admin ? '판매자 심사' : '판매 신청'}</h1>
    <p>포트폴리오용 모의 심사입니다. 실제 사업자 검증이 아니며 실제 사업자번호·개인정보·은행 정보를 입력하지 마세요.</p>
    {!ready ? <p role="status">{session.busy || !session.checked ? '로그인 상태를 확인하고 있습니다.' : '로그인이 필요합니다.'} <Link to="/login">로그인</Link></p> : <>
      {state.busy && <p role="status">정보를 확인하고 있습니다.</p>}
      {!admin && state.items && !session.member?.roles.includes('SELLER') && <form onSubmit={event => { void submit(event) }} aria-busy={state.busy}>
        <label htmlFor="seller-name">모의 매대 이름</label><input id="seller-name" name="displayName" required maxLength={160} disabled={state.busy} />
        <label htmlFor="seller-description">판매 계획</label><input id="seller-description" name="description" required maxLength={1000} disabled={state.busy} />
        <button disabled={state.busy}>판매 신청하기</button><p>심사 중이거나 승인된 신청이 있으면 중복 신청할 수 없습니다.</p>
      </form>}
      {session.member?.roles.includes('SELLER') && !admin && <p>판매자 자격이 있습니다. 구매자 기능도 계속 이용할 수 있습니다. 매대 편집은 다음 작업에서 제공됩니다.</p>}
      {state.items?.length === 0 && <p>신청 내역이 없습니다.</p>}
      {state.items?.map(item => <article key={item.id} aria-label={item.displayName}>
        <h2>{item.displayName}</h2><strong>{labels[item.status]}</strong><p>{item.description}</p>
        <p>신청 시각: {new Date(item.createdAt).toLocaleString('ko-KR')}</p>
        {admin && <p>신청자: {item.applicantId}</p>}
        <ol>{item.events.map((event, index) => <li key={index}>{labels[event.status]} · {event.reason} · {new Date(event.occurredAt).toLocaleString('ko-KR')}{admin && <> · 처리자: {event.actorId}</>}</li>)}</ol>
        {admin && item.status === 'PENDING' && <form onSubmit={event => { void submit(event, item.id) }} aria-busy={state.busy}>
          <label htmlFor={'decision-' + item.id}>심사 결과</label><select id={'decision-' + item.id} name="decision" disabled={state.busy}><option value="REJECTED">반려</option><option value="APPROVED">승인</option></select>
          <label htmlFor={'reason-' + item.id}>심사 사유</label><input id={'reason-' + item.id} name="reason" required maxLength={600} disabled={state.busy} />
          <button disabled={state.busy}>심사 결과 저장</button>
        </form>}
      </article>)}
      <div><button disabled={state.busy || state.page === 0} onClick={() => { void store.load(state.page - 1) }}>이전 목록</button>
        <span> {state.page + 1}페이지 </span><button disabled={state.busy || !state.hasMore} onClick={() => { void store.load(state.page + 1) }}>다음 목록</button></div>
      <button disabled={state.busy} onClick={() => { void store.load(state.page) }}>목록 새로고침</button>
    </>}
    {validation && <p role="alert">{validation}</p>}{state.error && <ApiErrorNotice error={state.error} />}{session.error && <ApiErrorNotice error={session.error} />}
    {state.notice && <p role="status">{state.notice}</p>}
    <button disabled={session.busy} onClick={() => { auth.invalidate() }}>로그인 상태 다시 확인</button>
  </section>
}
