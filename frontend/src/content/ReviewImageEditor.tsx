import {useEffect, useRef, useState} from 'react'
import {requestJson} from '../api/http'

export function ReviewImageEditor({path, version, saved}: {path:string; version:number; saved:()=>void}) {
  const [slot,setSlot] = useState(0)
  const [file,setFile] = useState<File | null>(null)
  const [pending,setPending] = useState<{key:string; remove:boolean} | null>(null)
  const [busy,setBusy] = useState(false)
  const [message,setMessage] = useState('')
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  async function send(command: {key:string;remove:boolean}) {
    if (!command.remove && (!file || file.size > 5*1024*1024)) {
      setMessage('5MiB 이하의 JPEG, PNG 또는 WebP 파일을 선택해 주세요.'); return
    }
    setPending(command); setBusy(true)
    const csrf = await requestJson('/api/auth/csrf')
    if (!active.current) return
    if (csrf.kind !== 'success' || !csrf.data || typeof csrf.data !== 'object' || !('token' in csrf.data) ||
        typeof csrf.data.token !== 'string') { setBusy(false); setMessage('로그인 상태를 확인해 주세요.'); return }
    const form = new FormData()
    if (file) form.set('file',file)
    const result = await requestJson(path+'/images/'+slot+'?key='+command.key+'&version='+version,{
      method:command.remove ? 'DELETE' : 'POST',headers:{'X-CSRF-TOKEN':csrf.data.token},body:command.remove ? undefined : form})
    if (!active.current) return
    setBusy(false)
    if (result.kind === 'success') { setPending(null); saved() }
    else if (result.kind === 'http' && result.status === 409) {
      setPending(null); saved()
    } else if (result.kind === 'http' && result.status < 500) {
      setPending(null); setMessage('이미지 형식과 최신 후기 상태를 확인해 주세요.')
    } else setMessage('이미지 저장 결과를 다시 확인해 주세요.')
  }
  return <div><fieldset disabled={!!pending}><legend>후기 이미지</legend>
    <label>이미지 위치 <select value={slot} onChange={e=>setSlot(Number(e.target.value))}>
      {[0,1,2,3,4].map(i=><option key={i} value={i}>{i+1}</option>)}</select></label>
    <label>이미지 파일 (5MiB 이하) <input type="file" accept="image/jpeg,image/png,image/webp"
      onChange={e=>setFile(e.target.files?.[0] ?? null)}/></label>
    <button disabled={!file} onClick={()=>void send({key:crypto.randomUUID(),remove:false})}>이미지 저장</button>
    <button onClick={()=>void send({key:crypto.randomUUID(),remove:true})}>선택 위치 이미지 삭제</button>
    </fieldset>{message && <p role="status">{message}</p>}
    {pending && !busy && <button onClick={()=>void send(pending)}>같은 이미지 요청으로 확인</button>}</div>
}
