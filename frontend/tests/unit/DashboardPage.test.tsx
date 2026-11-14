import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { DashboardPage } from '../../src/reporting/DashboardPage'

const member='ab44648b-c945-4122-b994-b4ef7691a373'
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
const snapshot={area:'SELLER',storeName:'실제 공개 매대',from:'2026-10-01',to:'2026-10-30',status:'DELAYED',
  generation:member,definitionVersion:1,sourceAsOf:'2026-11-11T05:00:00Z',completedAt:'2026-11-11T05:00:05Z',
  metrics:[{id:'NET_TRADED',basis:'PERIOD',unit:'KRW',value:'-9007199254740993111'},
    {id:'SHIPMENT_DELAYED',basis:'CURRENT',unit:'COUNT',value:'0'},
    {id:'REPLACEMENT_OLDEST_SECONDS',basis:'CURRENT',unit:'SECONDS',value:'7201'},
    {id:'SETTLEMENT_HELD',basis:'CURRENT',unit:'COUNT',value:'2'},
    {id:'CONFIRMED_PRODUCT_NET',basis:'PERIOD',unit:'KRW',value:'10000'}]}
function mount(auth:AuthStore,seller:boolean) {
  return render(<MemoryRouter><AuthContext.Provider value={auth}><DashboardPage seller={seller}/></AuthContext.Provider></MemoryRouter>)
}
it('retries a lost SYSTEM response with identical bytes and clears its form after revocation',async()=>{
  const auth=new AuthStore()
  const bodies:string[]=[]
  let allowed=true
  vi.stubGlobal('fetch',vi.fn(async(path:string,init:RequestInit)=>{
    if(path==='/api/auth/me')return json({id:member,roles:['ADMIN']})
    if(path==='/api/auth/csrf')return json({token:'current-csrf'})
    if(path==='/api/admin/system/dashboard/refresh'){
      bodies.push(String(init.body))
      if(bodies.length===1)throw new Error('response lost')
      allowed=false
      return json({},403)
    }
    if(allowed && path==='/api/admin/system/dashboard')return json({...snapshot,area:'SYSTEM',storeName:null,metrics:[]})
    return json({},403)
  }))
  mount(auth,false);await act(()=>auth.refresh())
  const input=await screen.findByLabelText('재집계 사유')
  fireEvent.change(input,{target:{value:'동일 지표 다시 확인'}})
  fireEvent.click(screen.getByRole('button',{name:'재집계 요청'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 요청 다시 확인'}))
  await screen.findByText('조회 가능한 지표가 없습니다.')
  expect(bodies).toHaveLength(2);expect(bodies[0]).toEqual(bodies[1])
  expect(JSON.parse(bodies[0])).toEqual({key:expect.any(String),reason:'동일 지표 다시 확인'})
  expect(screen.queryByLabelText('재집계 사유')).toBeNull()
  expect(screen.queryByDisplayValue('동일 지표 다시 확인')).toBeNull()
})
it('preserves signed exact amounts and hides old results after a newer period or logout',async()=>{
  const auth=new AuthStore()
  let complete:(r:Response)=>void=()=>{}
  let calls=0
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path==='/api/auth/me')return json({id:member,roles:['SELLER']})
    calls++
    if(calls===2||calls===4)return new Promise<Response>(resolve=>{complete=resolve})
    return json(calls===1?snapshot:{...snapshot,storeName:'다음 매대 조회',metrics:[]})
  }))
  mount(auth,true);await act(()=>auth.refresh())
  expect(await screen.findByText('-9,007,199,254,740,993,111원')).toBeVisible()
  expect(screen.getByText('0건')).toBeVisible()
  expect(screen.getByText('7,201초')).toBeVisible()
  expect(screen.getByText(/배송비와 수수료는 계산에서 제외/)).toBeVisible()
  expect(screen.getByRole('link',{name:'정산 상세에서 보류 사유 확인'})).toHaveAttribute('href','/seller/settlements')
  expect(screen.getByText('집계가 10분 넘게 지연됐습니다.')).toBeVisible()
  for(const date of ['2020-01-01','2021-01-01']){
    fireEvent.change(screen.getByLabelText('시작일'),{target:{value:date}})
    fireEvent.change(screen.getByLabelText('종료일'),{target:{value:date}})
    fireEvent.click(screen.getByRole('button',{name:'기간 조회'}))
  }
  expect(await screen.findByText('다음 매대 조회')).toBeVisible()
  await act(async()=>{complete(json(snapshot))})
  expect(screen.queryByText('실제 공개 매대')).toBeNull()
  fireEvent.click(screen.getByRole('button',{name:'기간 조회'}))
  act(()=>auth.stop())
  await act(async()=>{complete(json(snapshot))})
  expect(screen.queryByText('실제 공개 매대')).toBeNull()
  expect(screen.queryByText('-9,007,199,254,740,993,111원')).toBeNull()
})
it('distinguishes inaccessible areas, first unpublished generation, and refresh failure with no exposed totals',async()=>{
  const auth=new AuthStore()
  let retry=false
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path==='/api/auth/me')return json({id:member,roles:['ADMIN']})
    if(!path.includes('/support/'))return json({},403)
    return json({...snapshot,area:'SUPPORT',storeName:null,generation:null,sourceAsOf:null,completedAt:null,metrics:[],
      status:retry?'FAILED':'NOT_READY'})
  }))
  mount(auth,false);await act(()=>auth.refresh())
  expect(await screen.findByText('첫 집계 완료를 기다리고 있습니다.')).toBeVisible()
  expect(screen.queryByText('0건')).toBeNull()
  expect(screen.queryByText('거래 금액·정산')).toBeNull()
  expect(screen.queryByRole('button',{name:'재집계 요청'})).toBeNull()
  retry=true;fireEvent.click(screen.getByRole('button',{name:'기간 조회'}))
  expect(await screen.findByText('최근 집계에 실패했습니다.')).toBeVisible()
})
