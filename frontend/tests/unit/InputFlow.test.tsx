import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { AddToCart } from '../../src/pages/CartPages'
import { CartContext } from '../../src/cart/CartContext'
import type { CartStore } from '../../src/cart/CartStore'
import { AccountDetailsPage } from '../../src/pages/AccountPages'
import { AuthContext } from '../../src/auth/AuthContext'
import { AuthStore } from '../../src/auth/AuthStore'
const id='00000000-0000-4000-8000-000000000001'
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
test('수량 오류는 입력과 연결하고 포커스를 돌려 요청을 막으며 수정 후 한 번 제출한다',async()=>{
  const add=vi.fn();const state={ready:true,busy:false,notice:''}
  const store={getSnapshot:()=>state,subscribe:()=>()=>{},add} as unknown as CartStore
  const mounted=render(<MemoryRouter><CartContext value={store}><AddToCart sku={id} available={8}/></CartContext></MemoryRouter>)
  const user=userEvent.setup(),input=screen.getByLabelText('담을 수량')
  for(const value of ['', '0','100','1.5']){
    await user.clear(input);if(value)await user.type(input,value)
    await user.click(screen.getByRole('button',{name:'장바구니 담기'}))
    expect(screen.getByRole('alert')).toHaveTextContent('1~99')
    expect(input).toHaveAttribute('aria-invalid','true');expect(input).toHaveAccessibleDescription(/1~99/);expect(input).toHaveFocus();expect(add).not.toHaveBeenCalled()
  }
  await user.clear(input);await user.type(input,'3');await user.keyboard('{Enter}')
  expect(add).toHaveBeenCalledExactlyOnceWith(id,3)
  expect(screen.queryByRole('alert')).toBeNull()
  const busyState={...state,busy:true}; mounted.rerender(<MemoryRouter><CartContext value={{...store,getSnapshot:()=>busyState} as CartStore}><AddToCart sku={id} available={8}/></CartContext></MemoryRouter>)
  expect(screen.getByLabelText('담을 수량')).toBeDisabled()
})
test('배송지 오류 포커스와 서버 실패 뒤 임시 입력 유지·재시도·계정 변경 폐기를 확인한다',async()=>{
  let failed=true,writes=0
  vi.stubGlobal('fetch',vi.fn(async(path:string,init?:RequestInit)=>{
    if(path==='/api/auth/me')return json({id,roles:['BUYER']})
    if(path==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'test'})
    if(init?.method==='POST'){writes++;return failed?json({},500):json({...JSON.parse(String(init.body)),id,isDefault:true},201)}
    return json({addresses:[]})
  }))
  const auth=new AuthStore();await auth.refresh()
  render(<MemoryRouter><AuthContext value={auth}><AccountDetailsPage resource="addresses"/></AuthContext></MemoryRouter>)
  const user=userEvent.setup();await screen.findByLabelText('이름표')
  await user.click(screen.getByRole('button',{name:'배송지 추가'}))
  expect(screen.getByLabelText('이름표')).toHaveFocus();expect(screen.getByLabelText('이름표')).toHaveAttribute('aria-invalid','true');expect(writes).toBe(0)
  for(const [name,value] of Object.entries({이름표:'집',수령인:'구매자',전화번호:'01012345678',우편번호:'12345',주소:'서울시', '상세 주소':'101호'}))await user.type(screen.getByLabelText(name,{exact:true}),value)
  await user.click(screen.getByRole('button',{name:'배송지 추가'}))
  await waitFor(()=>expect(writes).toBe(1));await waitFor(()=>expect(screen.getByRole('button',{name:'배송지 추가'})).toBeEnabled())
  expect(screen.getByLabelText('주소',{exact:true})).toHaveValue('서울시');expect(screen.getByLabelText('상세 주소')).toHaveValue('101호')
  failed=false;await user.click(screen.getByRole('button',{name:'배송지 추가'}));await screen.findByText('저장했습니다.');expect(writes).toBe(2)
  await user.type(screen.getByLabelText('주소',{exact:true}),'다른 임시주소')
  act(()=>auth.stop());expect(screen.queryByLabelText('주소',{exact:true})).toBeNull();expect(screen.queryByDisplayValue('다른 임시주소')).toBeNull()
})
