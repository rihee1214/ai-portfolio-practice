import {useEffect,useState,useSyncExternalStore} from 'react'
import {useAuthStore} from '../auth/AuthContext'
import {requestJson} from '../api/http'
import {useInquiryCommand} from '../content/useInquiryCommand'
import {mediaSlotValid} from '../catalog/MediaStore'
import {Link} from 'react-router'

type Summary={reportId:string;state:string;decision:string|null}
const states:Record<string,string>={RECEIVED:'접수',INVESTIGATING:'조사 중',CLOSED:'종결'}
const decisions:Record<string,string>={VIOLATION:'위반 확인',NO_VIOLATION:'위반 미확인',INCONCLUSIVE:'확인 불가'}
const outcomes:Record<string,string>={
  RECEIVED:'새 신고를 접수했습니다.',
  EVIDENCE_ADDED:'기존 신고에 근거를 추가했습니다.',
  REOPEN_REQUESTED:'종결된 신고에 재개 요청을 남겼습니다.'
}

function ReportList({path,revision}:{path:string;revision:number}){
  const [rows,setRows]=useState<Summary[]>([])
  const [status,setStatus]=useState('신고 목록을 불러오는 중입니다.')
  useEffect(()=>{
    const controller=new AbortController()
    void requestJson(path,{signal:controller.signal,cache:'no-store'}).then(result=>{
      if(controller.signal.aborted)return
      if(result.kind==='success' && Array.isArray(result.data) && result.data.every(row=>
        row && typeof row.reportId==='string' && typeof row.state==='string' && Object.hasOwn(states,row.state))){
        setRows(result.data);setStatus(result.data.length?'':'접수된 신고가 없습니다.')
      }else{setRows([]);setStatus('신고 목록을 조회할 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[path,revision])
  return <>{status&&<p role="status">{status}</p>}<ul>{rows.map(row=><li key={row.reportId} id={row.reportId}>{row.reportId} · {states[row.state]} {row.decision&&decisions[row.decision]}</li>)}</ul></>
}

export function MyReports(){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return <section><h1>내 신고 상태</h1>{session.checked&&!session.busy&&session.member&&
    <ReportList key={session.member.id} path="/api/reports/mine" revision={0}/>}
    {session.checked&&!session.busy&&!session.member&&<Link to="/login">로그인 후 내 신고 확인</Link>}</section>
}

export function ProductReport({slug}:{slug:string}){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  if(!session.checked||session.busy||!session.member)return null
  return <ReportForm key={slug+session.member.id} title="상품 신고" path={'/api/products/'+encodeURIComponent(slug)+'/reports'}
    imagePath={'/api/media/public/products/'+encodeURIComponent(slug)}/>
}
const titles={REVIEW:'후기 신고',COMMENT:'댓글 신고',SELLER_REPLY:'판매자 답글 신고',QUESTION:'상품 문의 신고',ANSWER:'판매자 답변 신고'}
type Asset={slot:number;id:string}
export function ContentReport({kind,parent,id,edition,assets,onAccessLost}:{kind:keyof typeof titles;parent:string;id:string;edition:number;assets?:Asset[];onAccessLost?:()=>void}){
  const auth=useAuthStore(),session=useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  const path='/api/reports/content/'+[kind,parent,id,edition].join('/')
  if(!session.checked||session.busy||!session.member)return null
  return <ReportForm key={path+session.member.id} title={titles[kind]} path={path} assets={assets} onAccessLost={onAccessLost}
    imagePath={kind==='QUESTION'||kind==='ANSWER'?'/api/questions/'+parent+'/messages/'+id+'/images':undefined}/>
}
function ReportForm({path,title,imagePath,assets,onAccessLost}:{path:string;title:string;imagePath?:string;assets?:Asset[];onAccessLost?:()=>void}){
  const [open,setOpen]=useState(false),[reason,setReason]=useState(''),[revision,reload]=useState(0)
  const [notice,setNotice]=useState('')
  const [images,setImages]=useState<Asset[]>([]),[asset,selectAsset]=useState('')
  const [imageError,setImageError]=useState(false),[failure,setFailure]=useState('')
  useEffect(()=>{
    if(!open||!imagePath)return
    const controller=new AbortController()
    void requestJson(imagePath,{signal:controller.signal,cache:'no-store'}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success' && Array.isArray(r.data) && r.data.every(mediaSlotValid)){
        setImages(r.data.flatMap(row=>row.assetId?[{slot:row.slot,id:row.assetId}]:[]));setImageError(false)
      }else if(r.kind==='success' && r.data && typeof r.data==='object' && 'images' in r.data && Array.isArray(r.data.images)
        && r.data.images.every(i=>i && Number.isInteger(i.slot) && typeof i.asset==='string')){
        setImages(r.data.images.map(i=>({slot:i.slot,id:i.asset})));setImageError(false)
      }else if(onAccessLost && r.kind==='http' && [401,403,404].includes(r.status)){onAccessLost()}
      else{setImages([]);setImageError(true)}
    })
    return ()=>controller.abort()
  },[open,imagePath,onAccessLost])
  const cmd=useInquiryCommand((_request,data)=>{
    const outcome=data && typeof data==='object' && 'outcome' in data ? data.outcome : null
    setReason('');selectAsset('');setFailure('')
    setNotice(typeof outcome==='string' && Object.hasOwn(outcomes,outcome)?outcomes[outcome]:'내 신고 상태를 확인해 주세요.')
    reload(n=>n+1)
  },status=>{
    setReason('');selectAsset('');setNotice('');setOpen(false)
    if(onAccessLost && status && [401,403,404].includes(status)){onAccessLost();return}
    setFailure(status===429?'최근 24시간 동안 신규 신고 10건을 접수했습니다. 기존 신고에는 근거를 추가할 수 있습니다.':
      status===404?'상품이나 선택한 이미지가 더 이상 신고할 수 있는 상태가 아닙니다.':'')
  })
  const choices=assets??images
  return <section aria-label={title}><button aria-expanded={open} disabled={!!cmd.pending}
    onClick={()=>{setOpen(!open);setReason('');selectAsset('');setImages([]);setNotice('');setFailure('')}}>{open?'신고 닫기':title}</button>
    {open&&<><label>신고 사유 <textarea value={reason} disabled={!!cmd.pending} onChange={e=>setReason(e.target.value)}/></label>
      {choices.length>0&&<label>{title==='상품 신고'?'관련 상품 이미지':'관련 이미지'} <select disabled={!!cmd.pending} value={asset} onChange={e=>selectAsset(e.target.value)}>
        <option value="">지정하지 않음</option>{choices.map(image=><option key={image.id} value={image.id}>이미지 {image.slot+1}</option>)}</select></label>}
      {imageError&&<p>상품 이미지를 조회할 수 없습니다. 이미지 지정 없이 신고할 수 있습니다.</p>}
      <button disabled={!!cmd.pending||!reason.trim()} onClick={()=>void cmd.send({path,
        data:{key:crypto.randomUUID(),reason,assetId:asset||null}})}>신고 접수</button>
      {notice&&<p role="status">{notice}</p>}
      <h3>내 신고 상태</h3><ReportList key={revision} path="/api/reports/mine" revision={revision}/>
    </>}
    {(failure||cmd.error)&&<p role="alert">{failure||cmd.error}</p>}
    {cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 신고 요청으로 결과 확인</button>}
  </section>
}
