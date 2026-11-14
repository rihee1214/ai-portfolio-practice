import {useEffect,useState,useSyncExternalStore} from 'react'
import {Link,useSearchParams} from 'react-router'
import {requestJson} from '../api/http'
import {useAuthStore} from '../auth/AuthContext'
import {useInquiryCommand} from '../content/useInquiryCommand'

type Event={id:string;actor:string;reason:string;hidden:boolean;edition:number;targetVersion:number;occurredAt:string}
type State={kind:string;targetId:string;edition:number;targetVersion:number|null;hidden:boolean|null;activeHide:string|null;closeVersion:number|null;caseVersion:number|null;canHide:boolean;canRestore:boolean;history:Event[]}
const kinds:Record<string,string>={PRODUCT:'상품',REVIEW:'후기',COMMENT:'댓글',SELLER_REPLY:'판매자 답글'}
const uuid=/^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i
export function ModerationPage(){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  const [query,setQuery]=useSearchParams(),report=query.get('report'),product=query.get('product')
  const [legacy,setLegacy]=useState('')
  const id=report??product
  return <section><h1>콘텐츠 조치</h1><Link to="/admin/reports">신고 사건 선택</Link>
    <form onSubmit={e=>{e.preventDefault();if(uuid.test(legacy))setQuery({product:legacy})}}>
      <label>기존 비공개 상품 ID <input value={legacy} onChange={e=>setLegacy(e.target.value.trim())}/></label>
      <button disabled={!uuid.test(legacy)}>기존 상품 복원 조회</button></form>
    {session.checked&&!session.busy&&session.member&&id&&uuid.test(id)&&<Action key={[session.member.id,report,product].join(':')}
      path={report?'/api/admin/content/reports/'+id+'/moderation':'/api/admin/content/moderation/legacy-products/'+id} legacy={!report}/>}
  </section>
}
function Action({path,legacy}:{path:string;legacy:boolean}){
  const [view,setView]=useState<State|null>(null),[revision,refresh]=useState(0),[reason,setReason]=useState('')
  const [notice,setNotice]=useState('')
  const [publicReason,setPublicReason]=useState('')
  const reload=()=>{setView(null);setReason('');setPublicReason('');refresh(n=>n+1)}
  const command=useInquiryCommand((_request,data)=>{
    const id=data&&typeof data==='object'&&'id' in data?String(data.id):''
    setNotice('조치를 저장했습니다. 조치 ID '+id);reload()
  },status=>{setNotice(status===409?'상태가 변경됐습니다. 현재 상태를 확인해 주세요.':'현재 처리 권한을 확인해 주세요.');reload()})
  useEffect(()=>{
    const c=new AbortController()
    void requestJson(path,{cache:'no-store',signal:c.signal}).then(r=>{
      if(c.signal.aborted)return
      if(r.kind==='success'&&r.data&&typeof r.data==='object'&&'targetVersion' in r.data){setView(r.data as State)}
      else{setView(null);setNotice('현재 조치 대상을 조회할 수 없습니다.')}
    })
    return ()=>c.abort()
  },[path,revision])
  const permitted=view&&(view.canHide||view.canRestore)
  return <section aria-label="대상 조치">{view&&<>{view.hidden===null?<p>이전 후기 세대의 조치 이력입니다.</p>:<p>운영 숨김 상태 · {view.hidden?'비공개':'숨김 해제'}</p>}
    <p style={{overflowWrap:'anywhere'}}>{kinds[view.kind]} {view.targetId}</p>
    <p>{view.targetVersion!==null&&'대상 버전 '+view.targetVersion+' · '}세대 {view.edition}</p>
    {view.activeHide&&<p>숨김 조치 ID {view.activeHide}</p>}
    {!!view.history?.length&&<ol aria-label="조치 이력">{view.history.map(h=><li key={h.id} style={{overflowWrap:'anywhere'}}>
      {h.hidden?'비공개':'숨김 해제'} · {h.occurredAt} · 담당자 {h.actor} · 조치 {h.id} · 세대 {h.edition} · 버전 {h.targetVersion}<p>{h.reason}</p>
    </li>)}</ol>}
    {legacy&&view.hidden&&!view.canRestore&&<p>신고 사건에서 숨긴 상품입니다. <Link to="/admin/reports">원 사건에서 복원 확인</Link></p>}
    {permitted&&<fieldset disabled={!!command.pending}><label>조치 사유 <textarea value={reason} onChange={e=>setReason(e.target.value)}/></label>
      <label>대상자 공개 안내 (선택)<textarea maxLength={500} value={publicReason} onChange={e=>setPublicReason(e.target.value)}/></label><button disabled={!reason.trim()} onClick={()=>void command.send({path,data:{key:crypto.randomUUID(),hide:!view.hidden,
        caseVersion:view.caseVersion,closeVersion:view.closeVersion,edition:view.edition,targetVersion:view.targetVersion,sourceHide:view.activeHide,reason,...(publicReason.trim()?{publicReason:publicReason.trim()}:{})}})}>
        {view.hidden?'운영 숨김 해제':'운영 비공개'}</button></fieldset>}</>}
    {notice&&<p role="status">{notice}</p>}{command.error&&<p role="alert">{command.error}</p>}
    {command.pending&&!command.busy&&<button onClick={()=>void command.send(command.pending!)}>같은 조치 요청 확인</button>}
  </section>
}
