import {useEffect,useRef,useState,useSyncExternalStore} from 'react'
import {useAuthStore} from '../auth/AuthContext'
import {formatMoney} from '../promotion/QuoteStore'
import {refundStatusText} from './RefundStatusStore'
import {SystemRefundStore} from './SystemRefundStore'
import './system-refunds.css'

export function SystemRefundsPage(){
 const auth=useAuthStore(),[store]=useState(()=>new SystemRefundStore(auth)),v=useSyncExternalStore(store.subscribe,store.getSnapshot),error=useRef<HTMLParagraphElement>(null)
 useEffect(()=>{const disconnect=store.connect();window.addEventListener('focus',store.revalidate);return()=>{window.removeEventListener('focus',store.revalidate);disconnect()}},[store])
 useEffect(()=>{if(v.error)error.current?.focus()},[v.error])
 const disabled=v.busy||v.sending||v.uncertain||v.blocked,r=v.selected
 return <section className="system-refunds" aria-label="SYSTEM 환불 대사"><h1>환불 대사</h1><p>SYSTEM 권한으로 제공자 환불의 현재 상태와 근거를 확인합니다. 거래 지원의 반품·교환 결정은 별도 SUPPORT 업무입니다.</p><p>이 화면은 새 환불을 전송하지 않습니다. 귀속이 불명확한 UNKNOWN은 금액 예약을 유지하고 운영 확인으로 남깁니다.</p>
 {v.busy&&<p role="status">현재 환불 정보를 확인하고 있습니다.</p>}{v.sending&&<p role="status">조회 요청의 접수 결과를 확인하고 있습니다.</p>}{v.error&&<p role="alert" tabIndex={-1} ref={error}>{v.error}</p>}{v.notice&&<p role="status">{v.notice}</p>}
 <button disabled={v.busy||v.sending||v.uncertain} onClick={()=>void store.load()}>환불 대기열 다시 조회</button><button disabled={v.sending} onClick={store.revalidate}>SYSTEM 권한 다시 확인</button>
 <form aria-label="환불 시도 직접 조회" onSubmit={event=>{event.preventDefault();void store.select(v.lookupId)}}><label>조회할 환불 시도 ID<input value={v.lookupId} pattern="[0-9a-fA-F-]{36}" maxLength={36} required disabled={disabled} onChange={e=>store.edit('lookupId',e.target.value)}/></label><button disabled={disabled||!/^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.test(v.lookupId)}>이 시도 현재 근거 조회</button></form>
 {!v.busy&&v.available&&!v.error&&v.rows.length===0&&<p>조회할 대기 환불이 없습니다.</p>}
 <section aria-label="환불 대기열"><h2>현재 대기 환불</h2><p>최대 50건을 표시합니다. 상품 환불과 원 배송비는 별도 시도입니다.</p><ul>{v.rows.map(row=><li key={row.attemptId}><p>{row.purpose==='ITEM'?'상품':'원배송비'} 환불 · {formatMoney(row.amount)} · {row.mode==='DEMO'?'모의':'테스트'}</p><p>{refundStatusText(row)}</p><p>시도 {row.attemptId} · {row.sequence}회 · 버전 {row.version}</p><button disabled={disabled} onClick={()=>void store.select(row.attemptId)}>이 환불 근거 확인</button></li>)}</ul></section>
 {r&&<section aria-label="선택한 환불 대사"><h2>{r.purpose==='ITEM'?'상품':'원배송비'} 환불 상세</h2><p>{formatMoney(r.amount)} · {refundStatusText(r)}</p><p>시도 {r.attemptId} · 하위 주문 {r.sellerOrderId} · 원인 {r.claimId}</p><p>자동 조회 {r.automaticQueries}/5회 · 버전 {r.version}</p>{r.deadline&&<p>자동 조회 종료 기한 <time dateTime={r.deadline}>{new Date(r.deadline).toLocaleString('ko-KR')}</time></p>}{r.nextDue&&<p>다음 자동 확인 <time dateTime={r.nextDue}>{new Date(r.nextDue).toLocaleString('ko-KR')}</time></p>}<p>{r.providerReceipt?'최초 전송 응답의 귀속 영수증이 저장되어 있습니다.':'귀속 영수증이 없습니다. 금액이나 운영 기록만으로 환불 성공을 확정하지 않습니다.'}</p>
 {r.providerReference&&<p>검증된 결과 참조 {r.providerReference}</p>}<p>제공자 결과와 상품·배송비 정산, 반품·교환 완료는 별도 상태입니다.</p>
 <section aria-label="제공자 관측 근거"><h3>저장된 제공자 관측</h3>{v.evidence.length===0&&<p>표시할 검증 관측이 없습니다.</p>}<ol>{v.evidence.map((e,i)=><li key={i}><p>{e.operation==='POST'?'최초 전송':'같은 시도 조회'} · {e.state==='SUCCEEDED'?'성공 근거':'결과 불명확'}</p>{e.transactionKey&&<p>관측 거래 {e.transactionKey}</p>}{e.fingerprint?<p>검증된 응답 fingerprint {e.fingerprint}</p>:<p>검증된 응답 fingerprint가 없습니다.</p>}<time dateTime={e.observedAt}>{new Date(e.observedAt).toLocaleString('ko-KR')}</time></li>)}</ol></section>
 {['UNKNOWN','PROCESSING'].includes(r.state)&&<form onSubmit={event=>{event.preventDefault();void store.command('query')}}><fieldset disabled={disabled}><legend>같은 환불 시도 조회</legend><label>환불 대사 사유<input value={v.reason} maxLength={500} required onChange={e=>store.edit('reason',e.target.value)}/></label><button disabled={disabled||!v.reason.trim()}>같은 시도 결과 조회</button><label>운영 근거 참조<input value={v.evidenceReference} maxLength={2000} onChange={e=>store.edit('evidenceReference',e.target.value)}/></label><label>확인할 제공자 거래<input value={v.transactionKey} maxLength={200} onChange={e=>store.edit('transactionKey',e.target.value)}/></label><p>근거는 감사 기록으로 저장합니다. 서버에서 귀속을 검증할 수 없으면 성공으로 변경하지 않습니다.</p><button type="button" disabled={disabled||!v.reason.trim()||!v.evidenceReference.trim()||!v.transactionKey.trim()} onClick={()=>void store.command('reconcile')}>운영 근거 기록 후 같은 시도 조회</button></fieldset></form>}
 </section>}
 {v.uncertain&&<button disabled={v.busy||v.sending} onClick={()=>void store.retry()}>같은 요청으로 대사 결과 확인</button>}{v.receipt&&<p>명령 접수 {v.receipt.operationId} · 원 시도 버전 {v.receipt.acceptedVersion}. 접수 영수증은 환불 성공 증거가 아닙니다.</p>}
 </section>
}
