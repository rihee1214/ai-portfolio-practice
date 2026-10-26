import { useEffect,useState,useSyncExternalStore,type SubmitEvent } from 'react'
import { Link } from 'react-router'
import { useAuthStore } from '../auth/AuthContext'
import { CouponStore,CouponTemplateStore,couponStatus,type Template,type Terms } from '../promotion/CouponStore'
import { koreanTime } from '../promotion/PromotionStore'
import { ApiErrorNotice } from '../components/ApiErrorNotice'
const states={DRAFT:'초안',ACTIVE:'발급 중',STOPPED:'발급 중지',ARCHIVED:'보관'}
function Conditions({terms}:{terms:Terms}){return <><p>{terms.kind==='FIXED'?terms.value.toLocaleString('ko-KR')+'원 할인':terms.value/100+'% 할인'}{terms.cap!==null&&' · 최대 '+terms.cap.toLocaleString('ko-KR')+'원'}</p><p>상품 행사 후 {terms.minimum.toLocaleString('ko-KR')}원 이상 구매 시</p><p>사용: {koreanTime(terms.startsAt)}부터 {koreanTime(terms.endsAt)} 전까지 (한국 시간)</p></>}
export function CouponPage(){
 const auth=useAuthStore(),member=useSyncExternalStore(auth.subscribe,auth.getSnapshot);const [store]=useState(()=>new CouponStore(auth));const state=useSyncExternalStore(store.subscribe,store.getSnapshot)
 useEffect(()=>store.connect(),[store])
 return <section><h1>내 쿠폰</h1><p>쿠폰은 해당 매대의 회원 구매에 사용할 수 있습니다. 발급 당시 조건을 유지하며 최종 적용은 주문 준비에서 확인합니다.</p>
 {!member.checked||member.busy?<p role="status">로그인을 확인하고 있습니다.</p>:!member.member||member.error?<Link to="/login">로그인하고 쿠폰 확인</Link>:<>
 <button disabled={state.busy} onClick={()=>void store.load()}>쿠폰 목록 다시 확인</button>{state.busy&&<p role="status">쿠폰을 확인하고 있습니다.</p>}{state.error&&<p role="alert">{state.error}</p>}{state.notice&&<p role="status">{state.notice}</p>}
 <h2>보유 쿠폰</h2>{state.owned.map(c=><article key={c.id}><h3>{c.title}</h3><Link to={'/stores/'+c.storeSlug}>{c.storeName}</Link><p>{couponStatus(c)}</p><Conditions terms={c.terms}/></article>)}{state.ready&&!state.owned.length&&<p>보유 쿠폰이 없습니다.</p>}
 <nav aria-label="보유 쿠폰 페이지"><button disabled={state.busy||!state.ownedPage} onClick={()=>void store.load(state.ownedPage-1,state.availablePage)}>이전 보유 쿠폰</button><span>{state.ownedPage+1} 페이지</span><button disabled={state.busy||!state.ownedMore} onClick={()=>void store.load(state.ownedPage+1,state.availablePage)}>다음 보유 쿠폰</button></nav>
 <h2>받을 수 있는 쿠폰</h2>{state.available.map(t=><article key={t.id}><h3>{t.title}</h3><Link to={'/stores/'+t.storeSlug}>{t.storeName}</Link><Conditions terms={t.terms}/><p>발급: {koreanTime(t.issueStart)}부터 {koreanTime(t.issueEnd)} 전까지</p><button disabled={state.busy} onClick={()=>void store.claim(t.id)}>{t.title} 받기</button></article>)}
 <nav aria-label="발급 쿠폰 페이지"><button disabled={state.busy||!state.availablePage} onClick={()=>void store.load(state.ownedPage,state.availablePage-1)}>이전 발급 쿠폰</button><span>{state.availablePage+1} 페이지</span><button disabled={state.busy||!state.availableMore} onClick={()=>void store.load(state.ownedPage,state.availablePage+1)}>다음 발급 쿠폰</button></nav>
 </>}</section>
}
export function CouponTemplatePage(){
 const auth=useAuthStore();const [store]=useState(()=>new CouponTemplateStore(auth));const state=useSyncExternalStore(store.subscribe,store.getSnapshot);const [id,setId]=useState<string|null>(null);useEffect(()=>store.connect(),[store]);const item=state.items?.find(t=>t.id===id)??null
 return <section className="auth-card store-card"><h1>회원 쿠폰 관리</h1><p>조건 수정은 새 발급에만 반영됩니다. 발급 중지는 이미 받은 쿠폰의 사용 기간을 바꾸지 않습니다.</p>
 {state.error&&<ApiErrorNotice error={state.error}/>}<button disabled={state.busy} onClick={()=>{setId(null);void store.load()}}>쿠폰 템플릿 다시 확인</button>{state.notice&&<p role="status">{state.notice}</p>}
 <ul>{state.items?.map(t=><li key={t.id}><button disabled={state.busy} onClick={()=>setId(t.id)}>{t.title} · {states[t.state]}</button><span> 발급 {t.issuedCount}/{t.totalLimit}</span></li>)}</ul>
 <nav aria-label="템플릿 페이지"><button disabled={state.busy||!state.page} onClick={()=>{setId(null);void store.load(state.page-1)}}>이전 템플릿</button><span>{state.page+1} 페이지</span><button disabled={state.busy||!state.hasMore} onClick={()=>{setId(null);void store.load(state.page+1)}}>다음 템플릿</button></nav>
 <button disabled={state.busy} onClick={()=>setId(null)}>새 쿠폰</button>{state.items&&<TemplateForm key={item?item.id+':'+item.version:'new'} item={item} busy={state.busy} save={v=>store.save(item,v)}/>}</section>
}
const local=(s:string)=>new Date(Date.parse(s)+9*3600000).toISOString().slice(0,16)
function TemplateForm({item,busy,save}:{item:Template|null;busy:boolean;save:(v:Record<string,unknown>)=>void}){
 const [error,setError]=useState('');return <form onSubmit={(e:SubmitEvent<HTMLFormElement>)=>{e.preventDefault();const d=new FormData(e.currentTarget);const dates:Record<string,string>={};for(const key of ['issueStart','issueEnd','startsAt','endsAt']){const date=new Date(String(d.get(key))+':00+09:00');if(!Number.isFinite(date.getTime())){setError('기간을 확인해 주세요.');return}dates[key]=date.toISOString()}
 const value=Number(d.get('value')),cap=d.get('cap')===''?null:Number(d.get('cap')),minimum=Number(d.get('minimum')),totalLimit=Number(d.get('totalLimit'))
 if(![value,minimum,totalLimit,...cap===null?[]:[cap]].every(Number.isSafeInteger)||value<=0||minimum<0||totalLimit<1||cap!==null&&cap<0){setError('할인 값·최소 금액·발급 한도를 확인해 주세요.');return}setError('');save({title:d.get('title'),kind:d.get('kind'),value,cap,minimum,totalLimit,state:d.get('state'),...dates})
 }}><fieldset disabled={busy||item?.state==='ARCHIVED'}><legend>{item?'쿠폰 수정':'쿠폰 등록'}</legend><label>쿠폰 이름<input name="title" required maxLength={120} defaultValue={item?.title??''}/></label>
 <label htmlFor="coupon-kind">쿠폰 할인 방식</label><select id="coupon-kind" name="kind" defaultValue={item?.terms.kind??'FIXED'}><option value="FIXED">정액</option><option value="RATE">정률</option></select>
 <label>쿠폰 할인 값<input name="value" type="number" min="1" step="1" required defaultValue={item?.terms.value??1000}/></label><p>정액은 원, 정률은 100이 1%입니다.</p>
 <label>쿠폰 최대 할인 (원, 선택)<input name="cap" type="number" min="0" step="1" defaultValue={item?.terms.cap??''}/></label>
 <label>최소 구매 금액<input name="minimum" type="number" min="0" step="1" required defaultValue={item?.terms.minimum??0}/></label>
 <label>총 발급 한도<input name="totalLimit" type="number" min="1" step="1" required defaultValue={item?.totalLimit??100}/></label>
 {([['issueStart','발급 시작',item?.issueStart],['issueEnd','발급 종료',item?.issueEnd],['startsAt','사용 시작',item?.terms.startsAt],['endsAt','사용 종료',item?.terms.endsAt]] as const).map(([key,label,value])=><label key={key}>{label} (한국 시간)<input name={key} type="datetime-local" required defaultValue={value?local(value):''}/></label>)}
 <label htmlFor="coupon-state">쿠폰 상태</label><select id="coupon-state" name="state" defaultValue={item?.state??'DRAFT'}>{Object.entries(states).filter(([key])=>item||key==='DRAFT').map(([key,label])=><option key={key} value={key}>{label}</option>)}</select>
 {error&&<p role="alert">{error}</p>}<button type="submit">{item?'쿠폰 저장':'쿠폰 등록'}</button></fieldset></form>
}
