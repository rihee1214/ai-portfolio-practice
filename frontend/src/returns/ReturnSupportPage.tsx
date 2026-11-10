import {useState} from 'react'
import {ReturnPanel} from './ReturnPanel'
import {ExchangePanel} from '../exchanges/ExchangePanel'
export function ReturnSupportPage(){const [input,setInput]=useState(''),[number,setNumber]=useState('');return <section><h1>주문 반품 지원</h1><p>지원 권한으로 지정한 주문의 현재 반품과 교환 환불 상태를 확인합니다. 결과가 불명확한 환불은 같은 시도를 조회하고, 실패가 확인된 경우에만 사유를 기록해 새 시도를 요청합니다.</p><form onSubmit={event=>{event.preventDefault();if(/^O[0-9a-f]{32}$/.test(input))setNumber(input)}}><label>지원할 주문번호<input value={input} pattern="O[0-9a-f]{32}" maxLength={33} required onChange={event=>setInput(event.target.value)}/></label><button>이 주문 반품 조회</button></form>{number&&<><ReturnPanel actor="support" number={number}/><ExchangePanel key={number} actor="support" number={number} items={[]}/></>}</section>}
