import {useEffect, useState, useSyncExternalStore} from 'react'
import {requestJson} from '../api/http'
import {useAuthStore} from '../auth/AuthContext'
import {ReviewResponseForm, type ResponseRow} from './ReviewResponseForm'
import {ContentReport} from '../reports/ProductReport'

type Review = {id: string; rating: number; body: string; edition: number; refunded: boolean; canReply:boolean;
  responses: ResponseRow[]; images: {id: string; slot: number}[]}
type Summary = {reviews: Review[]; ratingSum: number; count: number}

function summary(value: unknown): value is Summary {
  if (!value || typeof value !== 'object' || !('reviews' in value) || !Array.isArray(value.reviews)) return false
  return 'count' in value && value.count === value.reviews.length &&
    'ratingSum' in value && typeof value.ratingSum === 'number' &&
    value.reviews.every(row => row && typeof row.id === 'string' && typeof row.body === 'string' &&
      Number.isInteger(row.rating) && row.rating >= 1 && row.rating <= 5 && Array.isArray(row.responses) && Array.isArray(row.images))
}
export function PublicReviews({slug}: {slug: string}) {
  const auth = useAuthStore()
  const {member} = useSyncExternalStore(auth.subscribe,auth.getSnapshot)
  return <ReviewList key={slug + ':' + (member?.id ?? '')} slug={slug} member={member}/>
}
function ReviewList({slug,member}: {slug:string;member:{id:string;roles:string[]} | null}) {
  const [data, setData] = useState<Summary | null>(null)
  const [error, setError] = useState(false)
  const [retry, reload] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    void requestJson('/api/products/' + encodeURIComponent(slug) + '/reviews', {signal: controller.signal}).then(result => {
      if (controller.signal.aborted) return
      if (result.kind === 'success' && summary(result.data)) {
        setData(result.data)
        setError(false)
      } else {
        setData(null)
        setError(true)
      }
    })
    return () => controller.abort()
  }, [slug, retry])
  return <section aria-label="상품 후기"><h2>상품 후기</h2>
    {error && <p role="alert">후기를 불러오지 못했습니다. <button onClick={() => reload(n => n + 1)}>다시 조회</button></p>}
    {data && (data.count ? <><p>평균 {(data.ratingSum / data.count).toFixed(1)}점 · 후기 {data.count}개</p>
      <ul>{data.reviews.map(row => <li key={row.id}><strong>{row.rating}점</strong>
        {row.refunded && <span>전량 환불된 구매</span>}
        <p style={{overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>{row.body}</p>
        {member && <ContentReport kind="REVIEW" parent={row.id} id={row.id} edition={row.edition} assets={row.images}/>}
        {row.images.map(image => <img key={image.id} src={'/api/reviews/'+row.id+'/images/'+image.id}
          alt={'후기 이미지 '+(image.slot+1)} style={{maxWidth:'100%'}}/>)}
        {row.responses.map(reply => <div key={reply.id}>{reply.kind === 'SELLER_REPLY' && <strong>판매자 답글</strong>}
          <p style={{overflowWrap:'anywhere'}}>{reply.body}</p>
          {member && <ContentReport kind={reply.kind} parent={row.id} id={reply.id} edition={row.edition}/>}
          {member && reply.mine && <ReviewResponseForm key={reply.version} id={row.id} edition={row.edition}
            kind={reply.kind} existing={reply} saved={() => reload(n => n+1)}/>}</div>)}
        {member && <ReviewResponseForm id={row.id} edition={row.edition} kind="COMMENT" saved={() => reload(n => n+1)}/>}
        {member && row.canReply && !row.responses.some(r => r.kind === 'SELLER_REPLY') &&
          <ReviewResponseForm id={row.id} edition={row.edition} kind="SELLER_REPLY" saved={() => reload(n => n+1)}/>}
        </li>)}</ul></> :
      <p>등록된 후기가 없습니다.</p>)}
  </section>
}
