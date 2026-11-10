import {expect,it,vi} from 'vitest'
import {fireEvent,render,screen,waitFor} from '@testing-library/react'
import {ReviewEditor} from '../../src/content/ReviewEditor'
import {AuthContext} from '../../src/auth/AuthContext'
import {AuthStore} from '../../src/auth/AuthStore'
import {PublicReviews} from '../../src/content/PublicReviews'

const json=(value:unknown)=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}})

it('retries an uncertain create with the same immutable command',async()=>{
  const sent:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/csrf'))return json({token:'token',headerName:'X-CSRF-TOKEN'})
    if(options?.method==='POST'){
      sent.push(String(options.body))
      if(sent.length===1)throw new TypeError('response lost')
      return json({reviewId:'r',version:1})
    }
    return json({eligible:true,existing:null})
  }))
  render(<ReviewEditor number="order" item="item"/>)
  fireEvent.change(await screen.findByLabelText('후기 본문'),{target:{value:'실제 후기'}})
  fireEvent.click(screen.getByRole('button',{name:'후기 등록'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 요청으로 결과 확인'}))
  await waitFor(()=>expect(sent).toHaveLength(2))
  expect(sent[1]).toBe(sent[0])
  expect(JSON.parse(sent[0])).toMatchObject({action:'CREATE',expectedVersion:0,body:'실제 후기',rating:5})
})

it('discards the old account draft and prevents posting after a delayed CSRF response',async()=>{
  let resolveToken!: (response: Response)=>void
  let posts=0
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/csrf'))return new Promise<Response>(resolve=>{resolveToken=resolve})
    if(options?.method==='POST')posts++
    return json({eligible:true,existing:null})
  }))
  const owner=render(<ReviewEditor key="member1" number="order" item="item"/>)
  fireEvent.change(await screen.findByLabelText('후기 본문'),{target:{value:'이전 회원의 비공개 초안'}})
  fireEvent.click(screen.getByRole('button',{name:'후기 등록'}))
  owner.rerender(<ReviewEditor key="member2" number="second" item="other"/>)
  await screen.findByLabelText('후기 본문')
  resolveToken(json({token:'old'}))
  await waitFor(()=>expect(screen.getByLabelText('후기 본문')).toHaveValue(''))
  expect(posts).toBe(0)
  expect(screen.queryByText('저장했습니다.')).toBeNull()
})

it('retains a fully refunded existing review and its public rating',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>json({count:1,ratingSum:4,reviews:[{id:'a',body:'구매 후기',rating:4,
    edition:1,refunded:true,responses:[],images:[]}]})))
  render(<AuthContext.Provider value={new AuthStore()}><PublicReviews slug="review-product"/></AuthContext.Provider>)
  expect(await screen.findByText('전량 환불된 구매')).toBeVisible()
  expect(screen.getByText('평균 4.0점 · 후기 1개')).toBeVisible()
  expect(screen.getByText('구매 후기')).toBeVisible()
})

it('allows editing an existing refunded review but denies rewriting a deleted one without current eligibility',async()=>{
  const state=(deleted:boolean)=>({eligible:false,existing:{id:'a',body:'기존 후기',rating:5,version:3,deleted}})
  vi.stubGlobal('fetch',vi.fn(async()=>json(state(false))))
  const first=render(<ReviewEditor number="original" item="item"/>)
  expect(await screen.findByRole('button',{name:'후기 수정'})).toBeEnabled()
  first.unmount()
  vi.stubGlobal('fetch',vi.fn(async()=>json(state(true))))
  render(<ReviewEditor number="original" item="item"/>)
  expect(await screen.findByText('구매 확정하고 환불되지 않은 수량이 있어야 작성할 수 있습니다.')).toBeVisible()
  expect(screen.queryByRole('button',{name:'후기 등록'})).toBeNull()
})

it('reloads the image version on conflict and replays an uncertain upload with the original file and key',async()=>{
  let lookup=0
  const sent:{path:string;file:FormDataEntryValue|null}[]=[]
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
    if(path.endsWith('/csrf'))return json({token:'t'})
    if(path.includes('/images/')){
      sent.push({path,file:(options.body as FormData).get('file')})
      if(sent.length===1)return new Response('{}',{status:409,headers:{'Content-Type':'application/json'}})
      if(sent.length===2)throw new TypeError('reply lost')
      return json({version:4})
    }
    lookup++
    return json({eligible:true,existing:{id:'r',rating:4,body:'후기',version:lookup===1?1:3,deleted:false}})
  }))
  render(<ReviewEditor number="order" item="item"/>)
  const file=new File(['abc'],'후기.png',{type:'image/png'})
  fireEvent.change(await screen.findByLabelText(/이미지 파일/),{target:{files:[file]}})
  fireEvent.click(screen.getByRole('button',{name:'이미지 저장'}))
  await waitFor(()=>expect(lookup).toBe(2))
  fireEvent.change(screen.getByLabelText(/이미지 파일/),{target:{files:[file]}})
  fireEvent.click(screen.getByRole('button',{name:'이미지 저장'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 이미지 요청으로 확인'}))
  await waitFor(()=>expect(sent).toHaveLength(3))
  expect(sent[0].path).toContain('version=1')
  expect(sent[1].path).toContain('version=3')
  expect(sent[1].path).toBe(sent[2].path)
  expect((sent[1].file as File).name).toBe(file.name)
  expect((sent[2].file as File).size).toBe(file.size)
})
