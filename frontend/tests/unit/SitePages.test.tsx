import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
import { SiteAdmin } from '../../src/site/SitePages'
import { WorkspaceLayout } from '../../src/layouts/WorkspaceLayout'
import { workspaces } from '../../src/navigation'

const user='ab44648b-c945-4122-b994-b4ef7691a373'
it('omits the preview notice for the three live site tools and retains the workspace overview notice',()=>{
  const admin=workspaces.find(w=>w.path==='/admin')!
  for(const path of ['/admin/publications','/admin/settings','/admin/jobs','/admin']){
    const view=render(<MemoryRouter initialEntries={[path]}><WorkspaceLayout workspace={admin}/></MemoryRouter>)
    expect(!!view.queryByText('화면 구조 미리보기')).toBe(path==='/admin')
    expect(view.getByRole('navigation',{name:'운영자 메뉴'})).toBeVisible()
    view.unmount()
  }
})
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
function mount(auth:AuthStore,area:'posts'|'settings'|'jobs'){
  return render(<MemoryRouter><AuthContext.Provider value={auth}><SiteAdmin area={area}/></AuthContext.Provider></MemoryRouter>)
}
it('resends the same shipping change after response loss and discards fields after revocation',async()=>{
  const auth=new AuthStore(),sent:string[]=[]
  let denied=false
  vi.stubGlobal('fetch',vi.fn(async(path:string,opts:RequestInit)=>{
    if(path==='/api/auth/me')return json({id:user,roles:['ADMIN']})
    if(path==='/api/auth/csrf')return json({token:'csrf'})
    if(opts.method==='POST'){sent.push(String(opts.body));if(sent.length===1)throw new Error('response lost');denied=true;return json({},403)}
    if(denied)return json({},403)
    if(path.endsWith('shipping-policy'))return json([{version:4,baseFee:3000,freeThreshold:50000,reason:'이전 변경'}])
    return json([{info:{siteName:'비공개 설정 관리',supportGuide:'고객 안내',version:1},reason:'처음'}])
  }))
  mount(auth,'settings');await act(()=>auth.refresh())
  fireEvent.change(await screen.findByLabelText('변경 사유'),{target:{value:'신규 주문만 적용'}})
  fireEvent.change(screen.getByLabelText('기본 배송비 (원)'),{target:{value:'4500'}})
  fireEvent.click(screen.getByRole('button',{name:'설정 저장'}))
  fireEvent.click(await screen.findByRole('button',{name:'같은 요청 다시 확인'}))
  await screen.findByText('설정을 조회할 수 없습니다.')
  expect(sent).toHaveLength(2);expect(sent[1]).toBe(sent[0]);expect(JSON.parse(sent[0])).toMatchObject({baseFee:4500,expectedVersion:4,reason:'신규 주문만 적용'})
  expect(screen.queryByDisplayValue('신규 주문만 적용')).toBeNull()
})
it('drops a delayed unpublished CONTENT list after logout and never exposes it in the next session',async()=>{
  const auth=new AuthStore();let resolve!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/auth/me'?json({id:user,roles:['ADMIN']}):new Promise<Response>(r=>{resolve=r})))
  mount(auth,'posts');await act(()=>auth.refresh())
  expect(await screen.findByText('불러오는 중입니다.')).toBeVisible()
  act(()=>auth.stop())
  await act(async()=>resolve(json([{id:user,version:1,kind:'NOTICE',state:'DRAFT',title:'비공개 초안',body:'내부 내용',alt:'',priority:0,
    destination:null,startsAt:null,endsAt:null,asset:null}])))
  expect(screen.queryByText(/비공개 초안/)).toBeNull();expect(screen.getByText('운영 권한이 필요합니다.')).toBeVisible()
})
