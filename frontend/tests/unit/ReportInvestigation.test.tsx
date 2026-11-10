import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {MemoryRouter} from 'react-router'
import {expect,it,vi} from 'vitest'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import {ReportQueue} from '../../src/reports/ReportQueue'

const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
const member={id:'00000000-0000-4000-8000-000000000001',roles:['ADMIN']}
const row={reportId:'case',state:'INVESTIGATING',mine:true,available:false,version:1}
const detail={...row,kind:'ANSWER',targetId:'target',evidence:[{key:'k',reason:'비밀 신고 사유',assetId:'file'}],history:[]}
it('hands off from the queue when private case detail is no longer accessible',async()=>{
  let saved:Record<string,unknown>|undefined
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/auth/csrf'))return json({token:'token'})
    if(path.includes('?'))return json(saved?[]:[row])
    if(path.endsWith('/commands')){saved=JSON.parse(String(init?.body));return json({version:2,state:row.state})}
    return json({},404)
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><MemoryRouter><ReportQueue/></MemoryRouter></AuthContext.Provider>)
  fireEvent.click(await screen.findByRole('button',{name:'사건 열기'}))
  await screen.findByText('사건 열람 권한을 확인해 주세요.')
  const successor='00000000-0000-4000-8000-000000000002'
  fireEvent.change(screen.getByLabelText('새 담당자 ID'),{target:{value:successor}})
  fireEvent.click(screen.getByRole('button',{name:'재배정'}))
  await screen.findByText('접수된 신고가 없습니다.')
  expect(saved).toMatchObject({version:row.version,action:'ASSIGN',operator:successor})
  expect(screen.queryByText('비밀 신고 사유')).toBeNull()
})
async function open(){
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={['/admin/reports']}><ReportQueue/></MemoryRouter></AuthContext.Provider>)
  fireEvent.click(await screen.findByRole('button',{name:'사건 열기'}))
  await screen.findByText('비밀 신고 사유')
  return auth
}
it('discards an investigator’s private history and late source response on logout',async()=>{
  let deliver!: (r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.includes('?'))return json([row])
    if(path.endsWith('/source'))return new Promise<Response>(resolve=>{deliver=resolve})
    return json(detail)
  }))
  const auth=await open()
  fireEvent.change(screen.getByLabelText('판단 사유'),{target:{value:'이전 담당자 초안'}})
  fireEvent.click(screen.getByRole('button',{name:'현재 원문 확인'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  act(()=>auth.stop())
  await act(async()=>deliver(json({body:'늦은 비밀 원문'})))
  expect(screen.queryByText('비밀 신고 사유')).toBeNull()
  expect(screen.queryByDisplayValue('이전 담당자 초안')).toBeNull()
  expect(screen.queryByText('늦은 비밀 원문')).toBeNull()
  expect(screen.queryByRole('link',{name:'신고한 첨부 보기'})).toBeNull()
})
it('clears history while rechecking a missing source and restores it only with current case permission',async()=>{
  let reads=0,deliver!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.includes('?'))return json([row])
    if(path.endsWith('/source'))return json({},404)
    if(reads++===0)return json(detail)
    return new Promise<Response>(resolve=>{deliver=resolve})
  }))
  await open()
  fireEvent.click(screen.getByRole('button',{name:'현재 원문 확인'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  expect(screen.queryByText('비밀 신고 사유')).toBeNull()
  await act(async()=>deliver(json(detail)))
  expect(await screen.findByText('비밀 신고 사유')).toBeVisible()
  expect(screen.getByText('현재 열 수 있는 원문이 없습니다.')).toBeVisible()
})
it('rechecks a previously visible source and rejects late success after assignment is lost',async()=>{
  let reads=0,sourceReads=0,deliver!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.includes('?'))return json(reads<=1?[row]:[])
    if(path.endsWith('/source')){
      sourceReads++
      if(sourceReads===1)return json({body:'이전 원문'})
      if(sourceReads===2)return new Promise<Response>(resolve=>{deliver=resolve})
      return json({},404)
    }
    return reads++===0?json(detail):json({},404)
  }))
  await open()
  fireEvent.change(screen.getByLabelText('판단 사유'),{target:{value:'보호할 초안'}})
  const button=screen.getByRole('button',{name:'현재 원문 확인'})
  fireEvent.click(button)
  await screen.findByText('이전 원문')
  fireEvent.click(button)
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  expect(screen.queryByText('이전 원문')).toBeNull()
  fireEvent.click(button)
  await waitFor(()=>expect(screen.queryByText('비밀 신고 사유')).toBeNull())
  await act(async()=>deliver(json({body:'늦은 성공'})))
  expect(screen.queryByText('늦은 성공')).toBeNull()
  expect(screen.queryByRole('link',{name:'신고한 첨부 보기'})).toBeNull()
  expect(screen.queryByDisplayValue('보호할 초안')).toBeNull()
  expect(sourceReads).toBe(3)
})
