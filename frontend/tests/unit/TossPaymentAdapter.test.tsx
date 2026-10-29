import {it,expect,vi} from 'vitest'
import {TossPaymentAdapter,readTossRedirect} from '../../src/payment/TossPaymentAdapter'
const setup={clientKey:'test_ck_fixture-public',orderId:'00000000-0000-0000-0000-000000000001',customerKey:'00000000-0000-0000-0000-000000000001',amount:'15000'}
it('requests SDK v2 CARD authentication from saved server data with only a public test key',async()=>{
 const requestPayment=vi.fn(async()=>{}),payment=vi.fn(()=>({requestPayment})),sdk=vi.fn(()=>({payment}));await new TossPaymentAdapter(sdk).request(setup,'O-order','https://shop.example');expect(sdk).toHaveBeenCalledWith(setup.clientKey);expect(payment).toHaveBeenCalledWith({customerKey:setup.customerKey});expect(requestPayment).toHaveBeenCalledWith({method:'CARD',amount:{currency:'KRW',value:15000},orderId:setup.orderId,orderName:'주문 O-order',successUrl:'https://shop.example/payments/O-order/success',failUrl:'https://shop.example/payments/O-order/failure'});expect(JSON.stringify(requestPayment.mock.calls)).not.toContain('test_sk')
})
it('rejects live keys unsafe amounts and malformed redirect values before SDK or confirmation',async()=>{
 const sdk=vi.fn();for(const invalid of [{...setup,clientKey:'live_ck_disabled'},{...setup,clientKey:'test_sk_private'},{...setup,amount:'9007199254740992'},{...setup,amount:'0'}])await expect(new TossPaymentAdapter(sdk).request(invalid,'O-order','https://shop.example')).rejects.toThrow();expect(sdk).not.toHaveBeenCalled();for(const query of ['?orderId=foreign&amount=15000&paymentKey=fixture','?orderId='+setup.orderId+'&amount=1&paymentKey=fixture','?orderId='+setup.orderId+'&amount=15000&paymentKey=fixture&paymentKey=other'])expect(readTossRedirect(query,setup.orderId,setup.amount)).toBeNull()
})
it('treats a matching redirect as untrusted confirmation input and never a paid result',()=>{
 const redirect=readTossRedirect('?orderId='+setup.orderId+'&amount=15000&paymentKey=fixture',setup.orderId,setup.amount);expect(redirect).toEqual({orderId:setup.orderId,amount:'15000',paymentKey:'fixture'});expect(redirect).not.toHaveProperty('status');expect(redirect).not.toHaveProperty('paid')
})
