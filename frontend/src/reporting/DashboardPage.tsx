import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { requestJson } from '../api/http'
import { useAuthStore } from '../auth/AuthContext'
import { useInquiryCommand } from '../content/useInquiryCommand'

const adminAreas = ['SUPPORT', 'CONTENT', 'ACCOUNTS', 'SETTLEMENTS', 'SYSTEM'] as const
const titles: Record<string, string> = {
  SELLER: '내 매대', SUPPORT: '거래·고객지원', CONTENT: '상품·콘텐츠', ACCOUNTS: '판매자 심사',
  SETTLEMENTS: '거래 금액·정산', SYSTEM: '집계 작업',
}
const details: Record<string, string> = {
  SELLER: '/seller/orders', SUPPORT: '/admin/orders', CONTENT: '/admin/reports',
  ACCOUNTS: '/admin/sellers', SETTLEMENTS: '/admin/settlements', SYSTEM: '/admin/dashboard',
}
const labels: Record<string, string> = {
  REFRESH_FAILURES: '집계 시점까지 기록된 갱신 실패 시도',
  RECOVERY_FEE_DEDUCTED: '상품 환불에서 공제한 회수비', PAID_ORDERS: '결제 완료 주문',
  PRODUCTS_REGISTERED: '등록 상품',
  PRODUCTS_PUBLIC: '현재 공개 상품',
  PRODUCTS_HIDDEN: '운영 숨김 상품',
  PRODUCTS_STOPPED: '판매 중지 상품',
  QUESTIONS_UNANSWERED: '미답변 상품 문의',
  STOCK_ON_HAND: '실재고',
  STOCK_RESERVED: '예약 재고',
  STOCK_AVAILABLE: '가용 재고',
  SHIPMENT_PREPARING: '발송 준비 배송',
  SHIPMENT_SHIPPED: '발송한 배송',
  SHIPMENT_IN_TRANSIT: '운송 중 배송',
  SHIPMENT_DELIVERED: '완료된 배송',
  SHIPMENT_DELAYED: '지연 신고 배송',
  SHIPMENT_FAILED: '실패 신고 배송',
  REPLACEMENT_SHIPPED: '발송한 교환 배송',
  REPLACEMENT_IN_TRANSIT: '교환 배송 중',
  REPLACEMENT_DELIVERED: '완료된 교환 배송',
  REPLACEMENT_OLDEST_SECONDS: '진행 중인 교환 배송의 최장 발송 경과시간',
  OPEN_CANCEL: '진행 중인 취소',
  OPEN_RETURN: '진행 중인 반품',
  OPEN_EXCHANGE: '진행 중인 교환',
  SHIPPED_ORIGINAL_UNITS: '발송한 원 주문 수량',
  OPEN_CANCEL_UNITS: '취소 처리 수량',
  OPEN_RETURN_UNITS: '반품 처리 수량',
  OPEN_EXCHANGE_UNITS: '교환 처리 수량',
  CASES_WAITING: '운영 답변 대기 문의',
  MY_CASES_WAITING: '집계 시점 내 담당 답변 대기 문의',
  APPLICATION_PENDING: '심사 대기 신청',
  APPLICATION_APPROVED: '승인 상태 신청',
  APPLICATION_REJECTED: '반려 상태 신청',
  APPLICATION_RECEIVED: '접수된 판매 신청',
  REPORT_RECEIVED: '접수 상태 신고',
  REPORT_INVESTIGATING: '조사 중 신고',
  REPORT_CLOSED: '종결 상태 신고',
  REPORT_RECEIVED_TOTAL: '기간 접수 신고',
  REPORT_CLOSURES: '기간 종결 사건',
  MODERATION_HIDE: '비공개 조치',
  MODERATION_RESTORE: '공개 복원',
  LEDGER_GROSS: '원장 총액',
  LEDGER_FEE: '원장 수수료',
  LEDGER_NET: '원장 순액',
  MOCK_PAYOUT: '모의 지급액',
  CARRY_OPENING: '최근 명세 기초 미회수 조정',
  CARRY_CLOSING: '최근 명세 기말 미회수 조정',
  BATCH_DRAFT: '정산 초안',
  BATCH_REVIEWED: '검토 완료 명세',
  BATCH_PAID_MOCK: '지급 완료 명세',
  BATCH_MISMATCH: '동일 명세의 원장 합계 불일치',
  SETTLEMENT_HELD: '미지급 명세의 보류 항목',
  ORDERS_CREATED: '생성 주문 수', PAID_AMOUNT: '결제 성공액', NET_TRADED: '기간 순거래액',
  PRODUCT_GROSS: '할인 전 상품액', PRODUCT_DISCOUNT: '상품 할인', COUPON_DISCOUNT: '쿠폰 할인',
  SHIPPING_CHARGED: '청구 배송비', REFUNDED_ITEM: '성공 상품 환불액', REFUNDED_SHIPPING: '성공 배송비 환불액',
  CONFIRMED_PRODUCT_NET: '구매확정 상품 순매출', PAYMENT_FAILED: '결제 실패 주문',
  PAYMENT_UNKNOWN: '결과 미확정 주문', PAYMENT_CREATED: '결제 시도 생성', PAYMENT_PROCESSING: '결제 처리 중',
}
type Metric = {id: string; basis: string; unit: string; value: string}
type View = {area: string; storeName: string|null; from: string; to: string; status: string; generation: string|null
  definitionVersion: number; sourceAsOf: string|null; completedAt: string|null; metrics: Metric[]}
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object'
function valid(v: unknown, area: string): v is View {
  return record(v) && v.area === area && (v.storeName === null || typeof v.storeName === 'string')
    && typeof v.from === 'string' && typeof v.to === 'string' && typeof v.status === 'string'
    && ['READY','NOT_READY','DELAYED','FAILED'].includes(v.status) && v.definitionVersion === 1
    && (v.generation === null ? v.sourceAsOf === null && v.completedAt === null
      : typeof v.generation === 'string' && typeof v.sourceAsOf === 'string' && typeof v.completedAt === 'string')
    && Array.isArray(v.metrics) && v.metrics.every(m => record(m) && typeof m.id === 'string'
      && typeof m.value === 'string' && /^-?\d+$/.test(m.value) && ['PERIOD','CURRENT'].includes(String(m.basis))
      && ['COUNT','KRW','UNITS','SECONDS'].includes(String(m.unit)))
}

export function DashboardPage({ seller }: {seller: boolean}) {
  const auth = useAuthStore()
  const session = useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return session.member && !session.busy && session.member.roles.includes(seller ? 'SELLER' : 'ADMIN')
    ? <Dashboard key={session.member.id + ':' + auth.getGeneration() + ':' + seller} seller={seller}/>
    : <p>현재 계정의 접근 권한을 확인해 주세요.</p>
}
function Dashboard({seller}: {seller: boolean}) {
  const [from,setFrom] = useState(''), [to,setTo] = useState('')
  const [query,setQuery] = useState({search:'', revision:0})
  const [result,setResult] = useState<{views: View[]; error: boolean}|null>(null)
  const reset = () => { setResult(null); setQuery(q => ({...q, revision:q.revision+1})) }
  useEffect(() => {
    const controller = new AbortController()
    void Promise.all((seller ? ['SELLER'] : adminAreas).map(async area => {
      const base = seller ? '/api/seller' : '/api/admin/' + area.toLowerCase()
      const res = await requestJson(base + '/dashboard' + query.search, {signal:controller.signal, cache:'no-store'})
      if (res.kind === 'success' && valid(res.data,area)) return res.data
      if (res.kind === 'http' && res.status === 403) return null
      throw new Error('조회 실패')
    })).then(data => {
      if (!controller.signal.aborted) {
        const views=data.filter((v):v is View => v !== null)
        if (new Set(views.map(v=>v.generation)).size>1) { setResult({views:[],error:true}); return }
        if (views.length===0) { setFrom(''); setTo('') }
        setResult({views,error:false})
      }
    }).catch(() => { if (!controller.signal.aborted) setResult({views:[], error:true}) })
    return () => controller.abort()
  }, [seller,query])
  return <section style={{overflowWrap:'anywhere'}}><h1>{seller ? '매대 대시보드' : '운영 대시보드'}</h1>
    <form onSubmit={e => { e.preventDefault(); setResult(null)
      setQuery(q => ({search:from && to ? '?' + new URLSearchParams({from,to}) : '',revision:q.revision+1})) }}>
      <label>시작일 <input type="date" value={from} required={!!to} onChange={e=>setFrom(e.target.value)}/></label>
      <label>종료일 <input type="date" value={to} required={!!from} min={from} onChange={e=>setTo(e.target.value)}/></label>
      <button>기간 조회</button></form>
    {!result && <p role="status">집계 결과를 조회하고 있습니다.</p>}
    {result?.error && <p role="alert">집계 결과를 조회하지 못했습니다. 기간과 현재 권한을 확인해 주세요.</p>}
    {result && !result.error && !result.views.length && <p>조회 가능한 지표가 없습니다.</p>}
    {result?.views.map(v => <article key={v.area}><h2>{v.storeName ?? titles[v.area]}</h2>
      {v.status === 'FAILED' && <p role="status">최근 집계에 실패했습니다.</p>}
      {v.status === 'DELAYED' && <p role="status">집계가 10분 넘게 지연됐습니다.</p>}
      {!v.generation ? <p>첫 집계 완료를 기다리고 있습니다.</p> : <>
        <p>서울 기준 {v.from} ~ {v.to} · 정의 버전 {v.definitionVersion}</p>
        <p>집계 기준 {v.sourceAsOf} · 완료 {v.completedAt}</p>
        {['PERIOD','CURRENT'].map(basis => v.metrics.some(m=>m.basis===basis) && <section key={basis}>
          <h3>{basis === 'PERIOD' ? '선택 기간의 거래' : '선택 기간과 무관한 기준 시점 잔량'}</h3>
          <dl>{v.metrics.filter(m=>m.basis===basis).map(m=><div key={m.id}><dt>{labels[m.id] ?? m.id}</dt>
            <dd>{BigInt(m.value).toLocaleString('ko-KR')}{m.unit==='KRW'?'원':m.unit==='UNITS'?'개':m.unit==='SECONDS'?'초':'건'}</dd></div>)}</dl></section>)}
        {v.metrics.some(m=>m.id==='CONFIRMED_PRODUCT_NET') && <p>구매확정 상품 순매출은 확정된 원주문 수량의 할인 후 상품액입니다. 배송비와 수수료는 계산에서 제외합니다.</p>}
        {v.metrics.some(m=>m.id==='SETTLEMENT_HELD') && <Link to={seller?'/seller/settlements':'/admin/settlements'}>정산 상세에서 보류 사유 확인</Link>}
        {v.metrics.some(m=>m.id==='NET_TRADED') && <p>기간 순거래액은 결제 성공액에서 같은 기간에 성공한 상품·배송비 환불액을 뺀 값입니다.</p>}
      </>}
      {v.area === 'SYSTEM' ? <Refresh onChange={reset}/> : <Link to={details[v.area]}>{titles[v.area]} 상세 업무</Link>}
    </article>)}</section>
}
function Refresh({onChange}: {onChange:()=>void}) {
  const [reason,setReason] = useState('')
  const command = useInquiryCommand(onChange,onChange)
  return <div><label>재집계 사유 <input maxLength={300} value={reason} disabled={!!command.pending}
    onChange={e=>setReason(e.target.value)}/></label>
    <button disabled={!reason.trim() || !!command.pending} onClick={()=>void command.send({
      path:'/api/admin/system/dashboard/refresh',data:{key:crypto.randomUUID(),reason:reason.trim()},
    })}>재집계 요청</button>
    {command.error && <p role="alert">{command.error}</p>}
    {command.pending && !command.busy && <button onClick={()=>command.pending && void command.send(command.pending)}>같은 요청 다시 확인</button>}
  </div>
}
