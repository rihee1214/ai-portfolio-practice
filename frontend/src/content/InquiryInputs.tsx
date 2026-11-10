import {useEffect,useRef,useState} from 'react'
import {requestJson} from '../api/http'
import {useInquiryCommand} from './useInquiryCommand'

type ImageView={version:number;images:{slot:number;asset:string}[]}
type ImageProps={base:string;query?:string;edit:boolean;onAccessLost:()=>void}
export function InquiryImages({base,query='',edit,onAccessLost}:ImageProps) {
  const [open,setOpen]=useState(false)
  return open?<ImageList base={base} query={query} edit={edit} onAccessLost={onAccessLost}/>:<button onClick={()=>setOpen(true)}>{edit?'첨부 이미지 관리':'첨부 이미지 보기'}</button>
}
function ImageList({base,query='',edit,onAccessLost}:ImageProps){
  const [view,setView]=useState<ImageView|null>(null),[file,choose]=useState<File|null>(null)
  const [slot,setSlot]=useState(0),[revision,reload]=useState(0),[error,setError]=useState('')
  const refresh=()=>{setView(null);choose(null);reload(i=>i+1)}
  const failedAssets=useRef(new Set<string>())
  function checkMissingImage(asset:string) {
    if(failedAssets.current.has(asset))return
    failedAssets.current.add(asset)
    reload(i=>i+1)
  }
  const denied=(status?:number)=>status===401||status===403||status===404
  const cmd=useInquiryCommand(refresh,status=>{if(denied(status))onAccessLost();else refresh()})
  const url=(p:string)=>query?p+'?'+query:p
  useEffect(()=>{
    const controller=new AbortController()
    void requestJson(url(base),{signal:controller.signal}).then(r=>{
      if(controller.signal.aborted)return
      if(r.kind==='success' && r.data && typeof r.data==='object' && 'images' in r.data && Array.isArray(r.data.images)){
        setView(r.data as ImageView);setError('')
      }else if(r.kind==='http'&&denied(r.status)){onAccessLost()}
      else{setView(null);choose(null);setError('첨부 이미지를 열 수 없습니다.')}
    })
    return ()=>controller.abort()
  },[base,query,revision]) // eslint-disable-line react-hooks/exhaustive-deps
  function change(remove:boolean){
    if(!view || !remove && (!file || file.size>5242880)){setError('5MiB 이하의 파일을 선택해 주세요.');return}
    const p=base+'/'+slot+'?'+new URLSearchParams(query+'&version='+view.version+'&key='+crypto.randomUUID())
    void cmd.send({path:p,method:remove?'DELETE':'POST',file:remove?undefined:file!})
  }
  return <div>{view?.images.map(i=><img key={i.asset} alt={'첨부 이미지 '+(i.slot+1)} src={url(base+'/'+i.asset)}
    onError={()=>checkMissingImage(i.asset)} style={{maxWidth:'100%'}}/>)}
    {edit && view && <fieldset disabled={!!cmd.pending}><legend>첨부 이미지</legend>
      <label>첨부 위치 <select value={slot} onChange={e=>setSlot(Number(e.target.value))}>{[0,1,2].map(i=><option key={i} value={i}>{i+1}</option>)}</select></label>
      <label>JPEG·PNG·WebP 파일 (장당 5MiB) <input type="file" accept="image/jpeg,image/png,image/webp"
        onChange={e=>choose(e.target.files?.[0]??null)}/></label>
      <button disabled={!file} onClick={()=>change(false)}>첨부 저장</button>
      <button disabled={!view.images.some(i=>i.slot===slot)} onClick={()=>change(true)}>첨부 삭제</button>
    </fieldset>}
    {(error||cmd.error)&&<p role="alert">{error||cmd.error}</p>}
    {cmd.pending&&!cmd.busy&&<button onClick={()=>void cmd.send(cmd.pending!)}>같은 첨부 요청으로 확인</button>}
  </div>
}
