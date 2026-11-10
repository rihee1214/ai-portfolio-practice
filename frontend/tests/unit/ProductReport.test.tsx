import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {expect,it,vi} from 'vitest'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import {ProductReport} from '../../src/reports/ProductReport'

const json=(data:unknown)=>new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}})
const member={id:'00000000-0000-4000-8000-000000000001',roles:['BUYER']}
const assetId='00000000-0000-4000-8000-000000000002'

it('retries the same product report and attachment after a lost response',async()=>{
  const sent:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/csrf'))return json({token:'csrf'})
    if(path.includes('/media/public/'))return json([{slot:0,assetId,alt:'정면',version:1,width:2,height:2}])
    if(options.method==='POST'){
      sent.push(String(options.body))
      if(sent.length===1)throw new TypeError('response lost')
      return json({reportId:'r',outcome:'EVIDENCE_ADDED'})
    }
    return json([])
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ProductReport slug="sample"/></AuthContext.Provider>)
  fireEvent.click(screen.getByRole('button',{name:'상품 신고'}))
  fireEvent.change(await screen.findByLabelText('관련 상품 이미지'),{target:{value:assetId}})
  fireEvent.change(screen.getByLabelText('신고 사유'),{target:{value:'사진 문제'}})
  fireEvent.click(screen.getByRole('button',{name:'신고 접수'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 신고 요청으로 결과 확인'}))
  await screen.findByText('기존 신고에 근거를 추가했습니다.')
  expect(sent).toHaveLength(2)
  expect(sent[0]).toBe(sent[1])
  expect(JSON.parse(sent[0])).toMatchObject({reason:'사진 문제',assetId})
})

it('discards a product report draft before a late CSRF response can submit it',async()=>{
  let csrf!: (r:Response)=>void
  const writes:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/csrf'))return new Promise<Response>(resolve=>{csrf=resolve})
    if(options.method==='POST')writes.push(path)
    return json([])
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ProductReport slug="sample"/></AuthContext.Provider>)
  fireEvent.click(screen.getByRole('button',{name:'상품 신고'}))
  fireEvent.change(screen.getByLabelText('신고 사유'),{target:{value:'이전 회원의 사유'}})
  fireEvent.click(screen.getByRole('button',{name:'신고 접수'}))
  await waitFor(()=>expect(csrf).toBeTypeOf('function'))
  act(()=>auth.stop())
  await act(async()=>{csrf(json({token:'old'}))})
  expect(screen.queryByDisplayValue('이전 회원의 사유')).toBeNull()
  expect(writes).toEqual([])
})
