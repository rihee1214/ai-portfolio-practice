export type ReturnPolicy={productId:string;revision:number;source:string;amount:string;version:string;basis:string;aggregation:string;repeat:string}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
export const decimalAmount=(v:unknown):v is string=>typeof v==='string'&&/^(0|[1-9][0-9]{0,18})$/.test(v)&&BigInt(v)<=9223372036854775807n
export function returnPolicyValid(v:unknown,product?:string):v is ReturnPolicy {
 return object(v)&&typeof v.productId==='string'&&/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v.productId)&&(product===undefined||v.productId===product)&&Number.isSafeInteger(v.revision)&&Number(v.revision)>=0&&['MIGRATED_DEFAULT_3000','PRODUCT_DEFAULT_3000','SELLER_PRODUCT'].includes(String(v.source))&&decimalAmount(v.amount)&&v.version==='P09-RETURN-PRODUCT-MAX-1'&&v.basis==='ORIGINAL_SHIPMENT'&&v.aggregation==='RETURNED_TARGET_CUMULATIVE_MAX'&&v.repeat==='DELTA_ONLY'
}
export const returnPolicyText=(p:ReturnPolicy)=>'변심 반품 회수비 '+BigInt(p.amount).toLocaleString('ko-KR')+'원 · 같은 원 배송의 반품 대상 중 가장 큰 금액을 적용하며, 추가 반품은 증가한 차액만 부과합니다.'
