import { useFormDraft } from '../forms/useFormDraft'
import { useEffect, useState, useSyncExternalStore, type SubmitEvent } from 'react'
import { AccountStore, type Address, type AddressFields, type Profile } from '../auth/AccountStore'
import { AuthEntryActions } from '../auth/AuthEntryActions'
import { useAuthStore } from '../auth/AuthContext'
import { ApiErrorNotice } from '../components/ApiErrorNotice'

export function AccountDetailsPage({ resource }: { resource: 'profile' | 'addresses' }) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe, auth.getSnapshot)
  const [store] = useState(() => new AccountStore(auth, resource))
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => { const disconnect = store.connect(); auth.invalidate(); return disconnect }, [auth, store])
  const ready = session.member && session.checked && !session.busy
  return <div className="auth-card"><h1>{resource === 'profile' ? '프로필' : '배송지'}</h1>
    {!ready ? session.busy || !session.checked ? <p role="status">로그인 상태를 확인하고 있습니다.</p>
      : !session.error && <><p>로그인이 필요합니다.</p><AuthEntryActions /></> : <>
      {state.busy && <p role="status">정보를 확인하고 있습니다.</p>}
      {state.profile && <ProfileForm key={JSON.stringify(state.profile)} profile={state.profile} store={store} busy={state.busy} />}
      {state.addresses && <AddressBook addresses={state.addresses} store={store} busy={state.busy} />}
      <button type="button" disabled={state.busy} onClick={() => { void auth.mutate('logout') }}>로그아웃</button>
    </>}
    {state.error && <ApiErrorNotice error={state.error} />}
    {session.error && <ApiErrorNotice error={session.error} />}
    {state.notice && <p role="status">{state.notice}</p>}
    <button type="button" disabled={session.busy} onClick={() => { auth.invalidate() }}>상태 다시 확인</button>
  </div>
}

function ProfileForm({ profile, store, busy }: { profile: Profile; store: AccountStore; busy: boolean }) {
  const [validation, setValidation] = useState('')
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const displayName = String(data.get('displayName') ?? '').trim()
    const phone = String(data.get('phone') ?? '').trim() || null
    if ([...displayName].length < 1 || [...displayName].length > 80 || (phone !== null && ([...phone].length < 7 || [...phone].length > 25))) {
      setValidation('표시 이름은 1~80자, 연락처는 비워 두거나 7~25자로 입력해 주세요.'); return
    }
    setValidation('')
    await store.save('PUT', { displayName, phone })
  }
  return <form onSubmit={event => { void submit(event) }} aria-busy={busy}>
    <label htmlFor="profile-email">이메일</label><input id="profile-email" value={profile.email} readOnly autoComplete="email" />
    <label htmlFor="display-name">표시 이름</label><input id="display-name" name="displayName" defaultValue={profile.displayName} required maxLength={160} disabled={busy} />
    <label htmlFor="profile-phone">연락처</label><input id="profile-phone" name="phone" type="tel" defaultValue={profile.phone ?? ''} maxLength={50} disabled={busy} />
    <p>연락처는 선택 사항이며 저장해도 전화 본인확인이 되지는 않습니다.</p>
    {validation && <p role="alert">{validation}</p>}
    <button disabled={busy}>프로필 저장</button>
  </form>
}

function AddressBook({ addresses, store, busy }: { addresses: Address[]; store: AccountStore; busy: boolean }) {
  const [editing, setEditing] = useState<Address | null>(null)
  return <>
    {addresses.length === 0 && <p>등록된 배송지가 없습니다.</p>}
    <p>배송지는 최대 20개까지 등록할 수 있습니다. 첫 배송지는 기본 배송지가 됩니다.</p>
    <div className="address-list">{addresses.map(address => <article key={address.id} aria-label={address.label}>
      <h2>{address.label}{address.isDefault && <span> · 기본 배송지</span>}</h2>
      <p>{address.recipient} · {address.phone}</p><p>{address.postalCode} {address.addressLine1} {address.addressLine2}</p>
      <button disabled={busy} onClick={() => { setEditing(address) }}>수정</button>
      <button disabled={busy} onClick={() => { setEditing(null); void store.save('DELETE', undefined, address.id) }}>삭제</button>
    </article>)}</div>
    {(editing || addresses.length < 20) && <AddressForm key={editing?.id ?? 'new'} address={editing} store={store} busy={busy} onCancel={() => { setEditing(null) }} />}
  </>
}

function AddressForm({ address, store, busy, onCancel }: { address: Address | null; store: AccountStore; busy: boolean; onCancel: () => void }) {
  const draft = useFormDraft({ label: address?.label ?? '', recipient: address?.recipient ?? '', phone: address?.phone ?? '', postalCode: address?.postalCode ?? '', addressLine1: address?.addressLine1 ?? '', addressLine2: address?.addressLine2 ?? '' })
  const fields: { name: keyof Omit<AddressFields, 'isDefault'>; label: string; max: number; required: boolean }[] = [
    { name: 'label', label: '이름표', max: 40, required: true },
    { name: 'recipient', label: '수령인', max: 80, required: true },
    { name: 'phone', label: '전화번호', max: 25, required: true },
    { name: 'postalCode', label: '우편번호', max: 5, required: true },
    { name: 'addressLine1', label: '주소', max: 200, required: true },
    { name: 'addressLine2', label: '상세 주소', max: 200, required: false },
  ]
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    if (busy) return
    const values = Object.fromEntries(fields.map(field => [field.name, draft.values[field.name].trim()])) as Omit<AddressFields, 'isDefault'>
    if (!draft.validate(event.currentTarget, () => {
      const invalid = fields.find(field => [...values[field.name]].length > field.max || (field.required && !values[field.name]))
      if (invalid) return { field: invalid.name, message: `${invalid.label} 입력 길이를 확인해 주세요. 최대 ${invalid.max}자입니다.` }
      if ([...values.phone].length < 7) return { field: 'phone', message: '전화번호는 7~25자로 입력해 주세요.' }
      if (!/^[0-9]{5}$/.test(values.postalCode)) return { field: 'postalCode', message: '우편번호는 숫자 5자리로 입력해 주세요.' }
      return null
    })) return
    if (await store.save(address ? 'PUT' : 'POST', { ...values, isDefault: data.get('isDefault') === 'on' }, address?.id)) onCancel()
  }
  return <form noValidate onSubmit={event => { void submit(event) }} aria-busy={busy}>
    <h2>{address ? '배송지 수정' : '새 배송지'}</h2>
    {fields.map(field => <div key={field.name}><label htmlFor={'address-' + field.name}>{field.label}</label>
      <input id={'address-' + field.name} {...draft.input(field.name)} required={field.required}
        maxLength={field.name === 'postalCode' ? 5 : field.max * 2} type={field.name === 'phone' ? 'tel' : 'text'}
        pattern={field.name === 'postalCode' ? '[0-9]{5}' : undefined} inputMode={field.name === 'postalCode' ? 'numeric' : undefined} disabled={busy} />
    </div>)}
    <label className="checkbox-label"><input type="checkbox" name="isDefault" defaultChecked={address?.isDefault ?? false} disabled={busy} />기본 배송지</label>
    {draft.error && <p id={draft.errorId} role="alert">{draft.error.message}</p>}
    <button disabled={busy}>{address ? '배송지 저장' : '배송지 추가'}</button>
    {address && <button type="button" disabled={busy} onClick={onCancel}>수정 취소</button>}
  </form>
}
