export type SandboxSetup={clientKey:string;orderId:string;amount:string;customerKey:string}
export type TossConfirmation={paymentKey:string;orderId:string;amount:string}
export type TossSdk=(clientKey:string)=>{payment:(options:{customerKey:string})=>{requestPayment:(options:unknown)=>Promise<void>}}
declare global {interface Window {TossPayments?:TossSdk}}
async function loadSdk():Promise<TossSdk>{
 if(window.TossPayments)return window.TossPayments
 await new Promise<void>((resolve,reject)=>{const script=document.createElement('script');script.src='https://js.tosspayments.com/v2/standard';script.async=true;script.onload=()=>resolve();script.onerror=()=>reject(new Error('결제창을 불러오지 못했습니다.'));document.head.append(script)})
 if(!window.TossPayments)throw new Error('결제창을 불러오지 못했습니다.')
 return window.TossPayments
}
export class TossPaymentAdapter {
 constructor(private sdk?:TossSdk){}
 async request(setup:SandboxSetup,number:string,origin:string):Promise<void>{
  if(!/^test_ck_[A-Za-z0-9_-]+$/.test(setup.clientKey)||!/^[0-9a-f-]{36}$/.test(setup.orderId)||setup.customerKey!==setup.orderId||!/^[1-9]\d*$/.test(setup.amount)||!Number.isSafeInteger(Number(setup.amount)))throw new Error('테스트 결제 설정을 확인해 주세요.')
  const base=new URL(origin);if(base.origin!==origin||!['http:','https:'].includes(base.protocol))throw new Error('결제 주소를 확인해 주세요.')
  const sdk=this.sdk??await loadSdk();await sdk(setup.clientKey).payment({customerKey:setup.customerKey}).requestPayment({method:'CARD',amount:{currency:'KRW',value:Number(setup.amount)},orderId:setup.orderId,orderName:'주문 '+number,successUrl:origin+'/payments/'+encodeURIComponent(number)+'/success',failUrl:origin+'/payments/'+encodeURIComponent(number)+'/failure'})
 }
}
export function readTossRedirect(search:string,orderId:string,amount:string):TossConfirmation|null{
 const query=new URLSearchParams(search),key=query.get('paymentKey')
 if(['paymentKey','orderId','amount'].some(name=>query.getAll(name).length!==1)||query.get('orderId')!==orderId||query.get('amount')!==amount||!key||key.length>200||[...key].some(c=>c.charCodeAt(0)<32||c.charCodeAt(0)===127))return null
 return {orderId,amount,paymentKey:key}
}
