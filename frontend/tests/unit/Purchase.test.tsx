import {policyFixture} from './returnPolicyFixture'
import { expect, test } from 'vitest'
import { purchaseSummary } from '../../src/cart/Purchase'
import type { Line } from '../../src/cart/CartStore'
function line(skuId: string, storeSlug: string, price: number, quantity: number, available = 10): Line {
  return { skuId, quantity, detail: { product: { slug: 'product-'+skuId, title: '상품', description: '', categoryId: skuId, categoryName: '생활', storeSlug, storeName: '같은 이름', minPrice: price, soldOut: available === 0,returnPolicy:policyFixture() }, offers: [{ id: skuId, price, available, options: [] }] } }
}
test('주문 준비는 매대 식별자로 묶고 같은 이름도 분리하며 소계와 합계를 계산한다', () => {
  const result = purchaseSummary([line('a','store-a',100,2),line('b','store-b',300,1),line('c','store-a',50,3)])
  expect(result.allowed).toBe(true); expect(result.groups.map(g => [g.slug,g.items.length,g.subtotal])).toEqual([['store-a',2,350],['store-b',1,300]])
  expect(result.total).toBe(650)
})
test('빈 장바구니와 비판매·품절·재고부족은 일부 품목만 진행하지 않고 전체 진입을 막는다', () => {
  expect(purchaseSummary([]).allowed).toBe(false)
  for (const bad of [{ skuId:'b',quantity:1,detail:null },line('b','store-b',100,1,0),line('b','store-b',100,3,2)]) {
    expect(purchaseSummary([line('a','store-a',100,1),bad]).allowed).toBe(false)
  }
})
