import {useEffect,useRef,useState} from 'react'
import {requestJson} from '../api/http'

export type InquiryRequest={path:string;method?:string;data?:object;file?:File}
export function useInquiryCommand(saved:(request:InquiryRequest,data:unknown)=>void,clear:(status?:number)=>void) {
  const [pending,setPending]=useState<InquiryRequest|null>(null)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const live=useRef(true)
  const sending=useRef(false)
  useEffect(()=>{live.current=true;return ()=>{live.current=false}},[])
  async function send(cmd:InquiryRequest) {
    if(sending.current)return
    sending.current=true;setBusy(true);setPending(cmd)
    try {
      const csrf=await requestJson('/api/auth/csrf')
      if(!live.current)return
      if(csrf.kind!=='success'||!csrf.data||typeof csrf.data!=='object'||!('token' in csrf.data)||typeof csrf.data.token!=='string'){
        clear(csrf.kind==='http'?csrf.status:undefined);setPending(null);setError('인증 정보를 확인해 주세요.');return
      }
      const headers:Record<string,string>={'X-CSRF-TOKEN':csrf.data.token}
      let body:BodyInit|undefined
      if(cmd.file){const f=new FormData();f.append('file',cmd.file);body=f}
      else if(cmd.data){headers['Content-Type']='application/json';body=JSON.stringify(cmd.data)}
      const r=await requestJson(cmd.path,{method:cmd.method??'POST',headers,body})
      if(!live.current)return
      if(r.kind==='success'){setPending(null);setError('');saved(cmd,r.data)}
      else if(r.kind==='http'&&r.status<500){
        setPending(null);clear(r.status);setError('접근 권한이나 현재 버전을 확인해 주세요.')
      }else setError('저장 결과를 확인하지 못했습니다.')
    }finally{sending.current=false;if(live.current)setBusy(false)}
  }
  return {pending,busy,error,send}
}

