import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {expect,it,vi} from 'vitest'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import {ManagedAccountsPage} from '../../src/pages/ManagedAccountsPage'

const id='00000000-0000-4000-8000-000000000001'
const active={id,state:'ACTIVE',version:4,administrator:false}
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
async function open(){
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ManagedAccountsPage/></AuthContext.Provider>)
  return auth
}
it('searches by exact email in the request body and only rereads state and audit after an uncertain suspension',async()=>{
  const email='operator-search@example.com',calls:{path:string;method:string;payload:Record<string,unknown>|null}[]=[]
  let lost=false
  vi.stubGlobal('fetch',vi.fn(async(path:string,init:RequestInit)=>{
    const payload=init.body?JSON.parse(String(init.body)):null
    calls.push({path,method:init.method??'GET',payload})
    if(path.endsWith('/auth/me'))return json({id,roles:['ADMIN']})
    if(path.endsWith('/csrf'))return json({token:'token'})
    if(path.endsWith('/search'))return json({members:payload.term===email?[active]:[],next:null})
    if(path.endsWith('/suspend')){lost=true;throw new Error('response lost')}
    return json({member:lost?{...active,state:'SUSPENDED',version:5}:active,events:lost?[{id,actor:id,before:'ACTIVE',after:'SUSPENDED',reason:'확인한 감사 기록',at:'2026-11-09'}]:[]})
  }))
  await open();await screen.findByText('검색 결과가 없습니다.')
  fireEvent.change(screen.getByLabelText('회원 ID 또는 정확한 이메일'),{target:{value:email}})
  fireEvent.click(screen.getByRole('button',{name:'회원 검색'}))
  fireEvent.click(await screen.findByRole('button',{name:id+' 상세'}))
  fireEvent.change(await screen.findByLabelText('계정 조치 사유'),{target:{value:'확인한 사유'}})
  fireEvent.click(screen.getByRole('button',{name:'로그인 정지'}))
  expect(await screen.findByText('확인한 감사 기록')).toBeVisible()
  const index=calls.findIndex(x=>x.path.endsWith('/suspend'))
  expect(calls[index].payload).toEqual({expectedVersion:4,reason:'확인한 사유'})
  expect(calls.slice(index+1).map(x=>[x.method,x.path])).toEqual([['GET','/api/admin/accounts/members/'+id]])
  expect(calls.some(x=>x.path.includes(email))).toBe(false)
  expect(screen.getByText(/처리 결과를 확정할 수 없습니다/)).toBeVisible()
  expect(screen.queryByDisplayValue('확인한 사유')).toBeNull()
})
it('clears the entire directory on detail permission loss and ignores the outstanding list response',async()=>{
  const email='restricted@example.com'
  let saved=false,late!:(r:Response)=>void,deny!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string,init:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json({id,roles:['ADMIN']})
    if(path.endsWith('/csrf'))return json({token:'token'})
    if(path.endsWith('/search')){
      if(saved)return new Promise<Response>(resolve=>{late=resolve})
      return json({members:JSON.parse(String(init.body)).term===email?[active]:[],next:null})
    }
    if(path.endsWith('/suspend')){saved=true;return json({status:'SUSPENDED'})}
    if(saved)return new Promise<Response>(resolve=>{deny=resolve})
    return json({member:active,events:[{id,reason:'비공개 감사 사유'}]})
  }))
  await open();await screen.findByText('검색 결과가 없습니다.')
  fireEvent.change(screen.getByLabelText('회원 ID 또는 정확한 이메일'),{target:{value:email}})
  fireEvent.click(screen.getByRole('button',{name:'회원 검색'}))
  fireEvent.click(await screen.findByRole('button',{name:id+' 상세'}))
  await screen.findByText('비공개 감사 사유')
  fireEvent.change(screen.getByLabelText('계정 조치 사유'),{target:{value:'폐기할 초안'}})
  fireEvent.click(screen.getByRole('button',{name:'로그인 정지'}))
  await waitFor(()=>{expect(late).toBeTypeOf('function');expect(deny).toBeTypeOf('function')})
  await act(async()=>deny(json({},403)))
  await act(async()=>late(json({members:[active],next:null})))
  expect(await screen.findByRole('alert')).toHaveTextContent('권한')
  expect(screen.queryByRole('button',{name:id+' 상세'})).toBeNull()
  expect(screen.queryByDisplayValue(email)).toBeNull()
  expect(screen.queryByText('비공개 감사 사유')).toBeNull()
  expect(screen.queryByDisplayValue('폐기할 초안')).toBeNull()
})
it('discards a late private account detail when the operator logs out',async()=>{
  let finish!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json({id,roles:['ADMIN']})
    if(path.endsWith('/csrf'))return json({token:'token'})
    if(path.endsWith('/search'))return json({members:[active],next:null})
    return new Promise<Response>(resolve=>{finish=resolve})
  }))
  const auth=await open()
  fireEvent.click(await screen.findByRole('button',{name:id+' 상세'}))
  await waitFor(()=>expect(finish).toBeTypeOf('function'))
  act(()=>auth.stop())
  await act(async()=>finish(json({member:active,events:[{id,reason:'늦은 감사 사유'}]})))
  expect(screen.queryByText('늦은 감사 사유')).toBeNull()
  expect(screen.queryByRole('region',{name:'회원 상세'})).toBeNull()
})
