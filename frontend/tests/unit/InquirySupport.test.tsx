import { expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SupportDesk } from '../../src/support/SupportDesk'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { notifyGuestAccessChange } from '../../src/guest/GuestAccessLifecycle'
import { ProductQuestions } from '../../src/content/ProductQuestions'

const json=(data:unknown)=>new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}})
const forbidden=()=>new Response('{}',{status:403,headers:{'Content-Type':'application/json'}})
const member={id:'00000000-0000-4000-8000-000000000001',roles:['BUYER']}
const privateQuestion={id:'q',visibility:'PRIVATE',body:'비밀 질문',answer:null,answerId:null,version:1,canAnswer:true,ownQuestion:true}
it('loads storefront questions only when opened and discards the private body when closed',async()=>{
  const reads:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    reads.push(path);return json([privateQuestion])
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ProductQuestions slug="p"/></AuthContext.Provider>)
  expect(reads).toHaveLength(0)
  fireEvent.click(screen.getByRole('button',{name:'상품 문의 열기'}))
  await screen.findByText('비밀 질문')
  expect(reads).toEqual(['/api/products/p/questions'])
  fireEvent.click(screen.getByRole('button',{name:'상품 문의 접기'}))
  expect(screen.queryByText('비밀 질문')).toBeNull()
})
it.each(['csrf','attachment'])('clears private question and drafts before a delayed reload after %s denial',async reason=>{
  let reads=0,deliver!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/csrf')||path.endsWith('/images'))return forbidden()
    if(reads++===0)return json([privateQuestion])
    return new Promise<Response>(resolve=>{deliver=resolve})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ProductQuestions slug="p" defaultOpen/></AuthContext.Provider>)
  await screen.findByText('비밀 질문')
  fireEvent.change(screen.getByLabelText('답변 본문'),{target:{value:'비밀 답변 초안'}})
  fireEvent.click(screen.getByRole('button',{name:reason==='csrf'?'답변 등록':'첨부 이미지 관리'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  expect(screen.queryByText('비밀 질문')).toBeNull()
  expect(screen.queryByDisplayValue('비밀 답변 초안')).toBeNull()
  expect(screen.queryByRole('button',{name:'같은 요청으로 결과 확인'})).toBeNull()
  await act(async()=>deliver(json([])))
})
it('keeps an authorized discussion when only a replaced image URL disappears',async()=>{
  let reads=0
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/images'))return json({version:++reads,images:[{slot:0,asset:reads===1?'old':'new'}]})
    return json([privateQuestion])
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><ProductQuestions slug="p"/></AuthContext.Provider>)
  fireEvent.click(screen.getByRole('button',{name:'상품 문의 열기'}))
  fireEvent.click(await screen.findByRole('button',{name:'첨부 이미지 관리'}))
  fireEvent.error(await screen.findByAltText('첨부 이미지 1'))
  await waitFor(()=>expect(screen.getByAltText('첨부 이미지 1').getAttribute('src')).toMatch(/new$/))
  expect(screen.getByText('비밀 질문')).toBeVisible()
})
it('clears the assigned case before the queue reload returns after reassignment',async()=>{
  let queueReads=0,deliver!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/auth/me'))return json(member)
    if(path.endsWith('/csrf'))return json({token:'t'})
    if(options.method==='POST')return json({})
    if(path.includes('/queue'))return queueReads++===0?json([{targetId:'ticket',kind:'ORDER',assigned:true,version:1}]):new Promise<Response>(resolve=>{deliver=resolve})
    return json({id:'ticket',kind:'ORDER',orderNumber:'O-support-order',sellerOrderId:'assigned-seller-order',version:1,messages:[{id:'m',body:'담당자 전용 본문',sender:'CUSTOMER',mine:false}]})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<AuthContext.Provider value={auth}><SupportDesk reader="OPERATOR"/></AuthContext.Provider>)
  fireEvent.click(screen.getByRole('button',{name:'지원 접수함 열기'}))
  fireEvent.click(await screen.findByRole('button',{name:'본문 열기'}))
  await screen.findByText('담당자 전용 본문')
  expect(screen.getByText('O-support-order')).toBeVisible()
  expect(screen.getByText('assigned-seller-order')).toBeVisible()
  fireEvent.change(screen.getByLabelText('메시지 본문'),{target:{value:'이전 담당자 초안'}})
  fireEvent.change(screen.getByLabelText('새 담당자 ID'),{target:{value:'next'}})
  fireEvent.click(screen.getByRole('button',{name:'재배정'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  expect(screen.queryByText('담당자 전용 본문')).toBeNull()
  expect(screen.queryByText('O-support-order')).toBeNull()
  expect(screen.queryByText('assigned-seller-order')).toBeNull()
  expect(screen.queryByDisplayValue('이전 담당자 초안')).toBeNull()
  await act(async()=>deliver(json([])))
})
it('discards guest drafts and prevents a pending CSRF response from writing after guest rotation', async()=>{
  let deliver!: (r:Response)=>void
  const posts:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/csrf'))return new Promise<Response>(resolve=>{deliver=resolve})
    if(options?.method==='POST')posts.push(path)
    return json([])
  }))
  render(<AuthContext.Provider value={new AuthStore()}><SupportDesk/></AuthContext.Provider>)
  fireEvent.click(screen.getByRole('button',{name:'지원 접수함 열기'}))
  fireEvent.change(await screen.findByLabelText('메시지 본문'),{target:{value:'이전 비회원의 비밀 초안'}})
  fireEvent.click(screen.getByRole('button',{name:'문의 접수하기'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  act(()=>notifyGuestAccessChange())
  await act(async()=>{deliver(json({token:'old'}))})
  expect(posts).toHaveLength(0)
  fireEvent.click(screen.getByRole('button',{name:'지원 접수함 열기'}))
  expect(await screen.findByLabelText('메시지 본문')).toHaveValue('')
})
it('drops late private case details when the authenticated order scope changes',async()=>{
  let deliver!: (r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path.includes('/cases/ticket?'))return new Promise<Response>(resolve=>{deliver=resolve})
    return json(path.includes('number=first')?[{id:'ticket',kind:'ORDER',version:1}]:[])
  }))
  const auth=new AuthStore()
  const panel=(number:string)=><AuthContext.Provider value={auth}><SupportDesk number={number} sellerOrder={number}/></AuthContext.Provider>
  const view=render(panel('first'))
  fireEvent.click(screen.getByRole('button',{name:'주문 문의 열기'}))
  fireEvent.click(await screen.findByRole('button',{name:'주문 문의 ticket'}))
  await waitFor(()=>expect(deliver).toBeTypeOf('function'))
  view.rerender(panel('second'))
  await act(async()=>{deliver(json({id:'ticket',messages:[{id:'x',sender:'CUSTOMER',body:'이전 주문 비밀'}]}))})
  expect(screen.queryByText('이전 주문 비밀')).toBeNull()
})

it('clears assigned order details and drafts on refund history read denial and discards a late attachment',async()=>{
  for(const status of [404,403,401]){
    let assigned=true
    let deliver!:(response:Response)=>void
    let imageSignal:AbortSignal|null=null
    vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
      if(path.endsWith('/auth/me'))return json(member)
      if(path.endsWith('/queue'))return json(assigned?[{targetId:'ticket',kind:'ORDER',assigned:true,version:3}]:[])
      if(path.endsWith('/confirmed-refunds'))return assigned?json([]):new Response('{}',{status,headers:{'Content-Type':'application/json'}})
      if(path.includes('/images?')){
        imageSignal=options.signal as AbortSignal
        return new Promise<Response>(resolve=>{deliver=resolve})
      }
      if(path==='/api/support/cases/ticket?reader=OPERATOR')return json({id:'ticket',kind:'ORDER',orderNumber:'O-private',sellerOrderId:'private-seller-order',version:3,messages:[{id:'m',body:'재배정 전 비밀 본문',sender:'CUSTOMER',mine:false}]})
      throw new Error(path)
    }))
    const auth=new AuthStore();await auth.refresh()
    const mounted=render(<AuthContext.Provider value={auth}><SupportDesk reader="OPERATOR"/></AuthContext.Provider>)
    fireEvent.click(screen.getByRole('button',{name:'지원 접수함 열기'}))
    fireEvent.click(await screen.findByRole('button',{name:'본문 열기'}))
    expect(await screen.findByText('재배정 전 비밀 본문')).toBeVisible()
    expect(screen.getByText('O-private')).toBeVisible()
    expect(screen.getByText('private-seller-order')).toBeVisible()
    fireEvent.click(screen.getByRole('button',{name:'회수·검수 기록 열기'}))
    fireEvent.change(await screen.findByLabelText('원 주문 상품 ID'),{target:{value:'private-item'}})
    fireEvent.change(screen.getByLabelText('메시지 본문'),{target:{value:'담당자 답변 초안'}})
    fireEvent.click(screen.getByRole('button',{name:'첨부 이미지 보기'}))
    await waitFor(()=>expect(deliver).toBeTypeOf('function'))
    assigned=false
    fireEvent.click(screen.getByRole('button',{name:'결과 조회'}))
    await waitFor(()=>expect(screen.queryByText('재배정 전 비밀 본문')).toBeNull())
    expect(imageSignal!.aborted).toBe(true)
    await act(async()=>deliver(json({version:3,images:[{slot:0,asset:'prior-private-image'}]})))
    for(const value of ['O-private','private-seller-order','재배정 전 비밀 본문'])expect(screen.queryByText(value)).toBeNull()
    for(const value of ['private-item','담당자 답변 초안'])expect(screen.queryByDisplayValue(value)).toBeNull()
    expect(screen.queryByRole('img',{name:'첨부 이미지 1'})).toBeNull()
    expect(screen.queryByRole('region',{name:'구매확정 후 하자 환불'})).toBeNull()
    mounted.unmount()
  }
})
