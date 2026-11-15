import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { readRuntimePolicy } from '../api/runtimePolicy'
import { RuntimePolicyContext, useRuntimePolicy, type PolicyState } from './RuntimePolicyContext'

export function RuntimePolicyProvider({ children }: { children: ReactNode }) {
  const [policy, setPolicy] = useState<PolicyState>({ mode: 'pending', restricted: true })
  useEffect(() => {
    const controller = new AbortController()
    void readRuntimePolicy(controller.signal).then(result => {
      if (!controller.signal.aborted) setPolicy(result ?? { mode: 'unavailable', restricted: true })
    })
    return () => controller.abort()
  }, [])
  return <RuntimePolicyContext value={policy}>{children}</RuntimePolicyContext>
}
export function RuntimeRestrictionNotice() {
  const policy = useRuntimePolicy()
  return <p role="status">{policy.mode === 'pending' ? '실행 정책을 확인하고 있습니다. 확인 전에는 발급과 테스트 결제를 사용할 수 없습니다.'
    : policy.mode === 'unavailable' ? '실행 정책을 확인하지 못해 발급과 테스트 결제를 사용할 수 없습니다. 잠시 후 화면을 새로 열어 주세요.'
      : '공개 데모에서는 가입·이메일 확인·비밀번호 복구·전화 인증·비회원 구매/복구·실제 Toss 및 테스트 결제를 지원하지 않습니다. 초대받은 검토자의 기존 개별 계정으로 로그인하고 모의 결제를 이용해 주세요.'}</p>
}
export function RuntimeFeature({ title, children }: { title: string; children: ReactNode }) {
  const policy = useRuntimePolicy()
  return policy.restricted ? <section aria-label={title}><h1>{title}</h1><RuntimeRestrictionNotice /><button type="button" disabled>{title}</button><p><Link to="/login">개별 계정으로 로그인</Link></p></section> : children
}
