import {it,expect,vi} from 'vitest'
import {GuestCart} from '../../src/cart/GuestCart'
import {CartStore} from '../../src/cart/CartStore'
import {AuthStore} from '../../src/auth/AuthStore'
it('guest cleanup preserves edited and newly added quantities and is idempotent',()=>{
 const memory=new Map<string,string>(),cart=new GuestCart({getItem:key=>memory.get(key)??null,setItem:(key,value)=>{memory.set(key,value)},removeItem:key=>{memory.delete(key)}})
 const a='00000000-0000-0000-0000-000000000001',b='00000000-0000-0000-0000-000000000002',c='00000000-0000-0000-0000-000000000003'
 cart.set(a,1);cart.set(b,2);const submitted=cart.capture();cart.set(b,3);cart.set(b,2);cart.set(c,1)
 expect(()=>cart.reconcile('order-1',submitted)).not.toThrow();expect(cart.read()).toEqual([{skuId:b,quantity:2},{skuId:c,quantity:1}]);cart.set(a,1);cart.reconcile('order-1',submitted);expect(cart.read()).toHaveLength(3)
})
it('guest cleanup preserves the same quantity deleted and readded after approval',()=>{
 const memory=new Map<string,string>(),cart=new GuestCart({getItem:key=>memory.get(key)??null,setItem:(key,value)=>{memory.set(key,value)},removeItem:key=>{memory.delete(key)}})
 const sku='00000000-0000-0000-0000-000000000001';cart.set(sku,1);const approved=cart.capture();cart.set(sku,0);cart.set(sku,1)
 expect(cart.capture()[0].revision).not.toBe(approved[0].revision);cart.reconcile('new-revision',approved);expect(cart.read()).toEqual([{skuId:sku,quantity:1}])
})
it('an empty approved cleanup is a successful no-op without a cart request',async()=>{
 const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher)
 const store=new CartStore(new AuthStore(),new GuestCart(localStorage),async work=>work())
 await expect(store.reconcilePurchase('order-empty',{owner:null,items:[]})).resolves.toBeUndefined();expect(fetcher).not.toHaveBeenCalled()
})
