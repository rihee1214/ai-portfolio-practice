import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {MemoryRouter} from 'react-router'
import {expect,it,vi} from 'vitest'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import {ModerationPage} from '../../src/moderation/ModerationPage'

it('discards the stale draft on conflict and ignores a history response after logout',async()=>{
  const report='10000000-0000-4000-8000-000000000001'
  const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
  let reads=0,finish!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json({id:report,roles:['ADMIN']})
    if(path.endsWith('/auth/csrf'))return json({token:'csrf'})
    if(init?.method==='POST')return json({},409)
    if(reads++===0)return json({edition:1,targetVersion:4,caseVersion:3,closeVersion:3,canHide:true,hidden:false})
    return new Promise<Response>(resolve=>{finish=resolve})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={['/admin/moderation?report='+report]}><ModerationPage/></MemoryRouter></AuthContext.Provider>)
  fireEvent.change(await screen.findByLabelText('조치 사유'),{target:{value:'폐기할 초안'}})
  fireEvent.click(screen.getByRole('button',{name:'운영 비공개'}))
  await waitFor(()=>expect(finish).toBeTypeOf('function'))
  expect(screen.queryByDisplayValue('폐기할 초안')).toBeNull()
  act(()=>auth.stop())
  await act(async()=>finish(json({targetVersion:5,history:[{id:'old',reason:'지난 계정 조사 사유'}]})))
  expect(screen.queryByText('지난 계정 조사 사유')).toBeNull()
  expect(screen.queryByRole('region',{name:'대상 조치'})).toBeNull()
})

it('routes a migrated product hidden by a later case back to the report instead of offering legacy restoration',async()=>{
  const product='10000000-0000-4000-8000-000000000001'
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>new Response(JSON.stringify(path.endsWith('/auth/me')?{id:product,roles:['ADMIN']}:
    {edition:0,targetVersion:3,hidden:true,activeHide:null,canRestore:false,canHide:false}),{headers:{'Content-Type':'application/json'}})))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={['/admin/moderation?product='+product]}><ModerationPage/></MemoryRouter></AuthContext.Provider>)
  expect(await screen.findByRole('link',{name:'원 사건에서 복원 확인'})).toHaveAttribute('href','/admin/reports')
  expect(screen.queryByRole('button',{name:'운영 숨김 해제'})).toBeNull()
})

it('retries a lost moderation receipt with the same closure, target version and request key',async()=>{
  const report='10000000-0000-4000-8000-000000000001'
  const commands:Record<string,unknown>[]=[]
  const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json({id:report,roles:['ADMIN']})
    if(path.endsWith('/auth/csrf'))return json({token:'csrf'})
    if(init?.method==='POST'){
      commands.push(JSON.parse(String(init.body)))
      return commands.length===1?json({},503):json({id:'accepted'})
    }
    return json({edition:0,targetVersion:5,hidden:commands.length===2,activeHide:commands.length===2?'accepted':null,closeVersion:3,caseVersion:4,canHide:!commands.length})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={['/admin/moderation?report='+report]}><ModerationPage/></MemoryRouter></AuthContext.Provider>)
  fireEvent.change(await screen.findByLabelText('조치 사유'),{target:{value:'위반 확인'}})
  fireEvent.click(screen.getByRole('button',{name:'운영 비공개'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 조치 요청 확인'}))
  await waitFor(()=>expect(screen.getByText('숨김 조치 ID accepted')).toBeVisible())
  expect(commands).toHaveLength(2);expect(commands[1]).toEqual(commands[0])
  expect(commands[0]).toMatchObject({hide:true,edition:0,targetVersion:5,closeVersion:3,caseVersion:4,sourceHide:null})
})
