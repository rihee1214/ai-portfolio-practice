import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import { CheckoutPage } from '../../src/pages/PurchasePages'
import { WishlistPage } from '../../src/pages/WishlistPages'
import { CartContext } from '../../src/cart/CartContext'
import { CartStore } from '../../src/cart/CartStore'
import { GuestCart } from '../../src/cart/GuestCart'
import { AuthStore } from '../../src/auth/AuthStore'
import { AuthContext } from '../../src/auth/AuthContext'
const one='00000000-0000-4000-8000-000000000001',two='00000000-0000-4000-8000-000000000002'
const json=(data: unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
beforeEach(()=>localStorage.clear())
test('주문 준비는 재조회 대기·오류·계정 전환과 늦은 응답에서 이전 금액을 숨긴다',async()=>{
  let owner=one,hold=false,fail=false,available=5
  let release: ((r:Response)=>void)|undefined
  const view=(name:string)=>({version:0,items:[{skuId:one,quantity:2,detail:{product:{slug:'product-one',title:name,description:'',categoryId:one,categoryName:'생활',storeSlug:'store-one',storeName:name,minPrice:100,soldOut:available===0},offers:[{id:one,price:100,available,options:[]}]}}]})
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
    if(path==='/api/auth/me')return json({id:owner,roles:['BUYER']})
    if(hold)return new Promise<Response>(resolve=>{release=resolve})
    return fail?json({},500):json(view(owner===one?'A 내역':'B 내역'))
  }))
  const auth=new AuthStore();await auth.refresh()
  const store=new CartStore(auth,new GuestCart(localStorage),work=>work());const disconnect=store.connect()
  const mounted=render(<MemoryRouter><CartContext value={store}><CheckoutPage/></CartContext></MemoryRouter>)
  expect(await screen.findByRole('region',{name:'A 내역 준비 상품'})).toBeVisible()
  const hidden=()=>{expect(screen.queryByRole('region')).toBeNull();expect(screen.queryByText(/상품 금액 합계/)).toBeNull()}
  hold=true;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}));hidden()
  await waitFor(()=>expect(release).toBeDefined())
  await act(async()=>{release!(json({},500))});hidden()
  hold=false;fail=false;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
  expect(await screen.findByRole('region',{name:'A 내역 준비 상품'})).toBeVisible()
  for(const stock of [0,1]){
    available=stock;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
    expect(await screen.findByText('판매 불가·품절·재고 부족 항목을 장바구니에서 확인해 주세요.')).toBeVisible();hidden()
  }
  available=5;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
  expect(await screen.findByRole('region',{name:'A 내역 준비 상품'})).toBeVisible()
  hold=true;release=undefined;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
  await waitFor(()=>expect(release).toBeDefined());const old=release!
  owner=two;hold=false;fail=true
  await act(async()=>{await auth.refresh()});hidden()
  await act(async()=>{old(json(view('A 내역')))});hidden()
  fail=false;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
  expect(await screen.findByRole('region',{name:'B 내역 준비 상품'})).toBeVisible()
  expect(screen.queryByRole('region',{name:'A 내역 준비 상품'})).toBeNull()
  mounted.unmount();disconnect()
})
test('주문서 직접 진입은 새 장바구니를 읽어 두 매대 금액을 표시하고 비판매 변경은 차단한다',async()=>{
  let unavailable=false;let reads=0
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path==='/api/auth/me')return json({},401)
    if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'test'})
    reads++
    return json({version:0,items:JSON.parse(String(init?.body)).items.map((row:{skuId:string;quantity:number})=>({...row,detail:unavailable?null:{product:{slug:'product-'+row.skuId,title:'상품',description:'',categoryId:one,categoryName:'생활',storeSlug:row.skuId===one?'store-one':'store-two',storeName:row.skuId===one?'첫 매대':'둘째 매대',minPrice:100,soldOut:false},offers:[{id:row.skuId,price:100,available:5,options:[]}]}}))})
  }))
  const auth=new AuthStore();await auth.refresh();const guest=new GuestCart(localStorage);guest.set(one,2);guest.set(two,3)
  const store=new CartStore(auth,guest,work=>work());await store.refresh()
  render(<MemoryRouter><CartContext value={store}><CheckoutPage/></CartContext></MemoryRouter>)
  await waitFor(()=>expect(reads).toBe(2))
  expect(await screen.findByRole('region',{name:'첫 매대 준비 상품'})).toBeVisible()
  expect(screen.getByRole('region',{name:'둘째 매대 준비 상품'})).toBeVisible()
  expect(screen.getByText('상품 금액 합계: 500원')).toBeVisible()
  expect(screen.queryByRole('button',{name:'결제'})).toBeNull()
  unavailable=true;await userEvent.click(screen.getByRole('button',{name:'준비 내역 다시 확인'}))
  expect(await screen.findByText('판매 불가·품절·재고 부족 항목을 장바구니에서 확인해 주세요.')).toBeVisible()
  expect(screen.queryByRole('region',{name:'첫 매대 준비 상품'})).toBeNull()
})
test('빈 장바구니의 주문서 직접 주소는 준비 화면을 열지 않는다',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>json({},401)))
  const auth=new AuthStore();await auth.refresh();const store=new CartStore(auth,new GuestCart(localStorage),work=>work())
  render(<MemoryRouter><CartContext value={store}><CheckoutPage/></CartContext></MemoryRouter>)
  expect(await screen.findByText('장바구니가 비어 있어 주문서를 준비할 수 없습니다.')).toBeVisible()
  expect(screen.queryByText(/상품 금액 합계/)).toBeNull()
})
test('찜 화면은 비판매 정보를 숨기고 삭제하며 로그아웃 후 목록을 숨긴다',async()=>{
  let member=true,removed=false
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path==='/api/auth/me')return member?json({id:one,roles:['BUYER']}):json({},401)
    if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'test'})
    if(init?.method==='DELETE')removed=true
    return json({items:removed?[]:[{productId:two,detail:null}]})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<MemoryRouter><AuthContext value={auth}><WishlistPage/></AuthContext></MemoryRouter>)
  expect(await screen.findByText('판매할 수 없는 상품')).toBeVisible()
  await userEvent.click(screen.getByRole('button',{name:'찜 해제'}))
  expect(await screen.findByText('찜한 상품이 없습니다.')).toBeVisible()
  member=false;await auth.refresh()
  expect(await screen.findByText('찜한 상품은 로그인 후 확인할 수 있습니다.')).toBeVisible()
})
