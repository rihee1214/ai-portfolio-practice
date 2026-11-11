import { NavLink, Outlet, useLocation, matchPath } from 'react-router'
import type { Workspace } from '../navigation'

export function WorkspaceLayout({ workspace }: { workspace: Workspace }) {
  const location = useLocation()
  const sellerReview = workspace.path === '/admin' && matchPath('/admin/sellers', location.pathname)
  const storeEditor = workspace.path === '/seller' && matchPath('/seller/store', location.pathname)
  const operations = workspace.path === '/admin' && matchPath('/admin/stores', location.pathname)
  const catalog = matchPath('/admin/catalog', location.pathname) || matchPath('/seller/products', location.pathname)
  const shipping = workspace.path === '/seller' && location.pathname.startsWith('/seller/orders')
  return (
    <div className="workspace">
      <aside className="workspace-sidebar">
        <p className="eyebrow">{workspace.label} 공간</p>
        <p className="workspace-label">{workspace.title}</p>
        <nav className="workspace-nav" aria-label={`${workspace.label} 메뉴`}>
          <NavLink to={workspace.path} end>한눈에 보기</NavLink>
          {workspace.features.map((feature) => <NavLink key={feature.path} to={`${workspace.path}/${feature.path}`}>{feature.title}</NavLink>)}
        </nav>
      </aside>
      <div className="workspace-content">
        {!['/admin/support','/admin/reports','/seller/questions','/seller/settlements','/admin/settlements'].includes(location.pathname) && <div className="preview-notice">
          <strong>{shipping ? '판매 주문·모의 배송' : catalog ? '카테고리·상품 기본 관리' : operations ? '매대 운영 조치' : storeEditor ? '매대 기본 관리' : sellerReview ? '모의 판매자 심사' : '화면 구조 미리보기'}</strong>
          <p>{shipping ? '본인 매대의 실제 주문과 배송 이력을 확인합니다. 모의 배송이며 실제 택배 추적은 제공하지 않습니다.' : catalog ? '공간 접근 권한을 확인했습니다. 기본 정보·옵션·가격·공개 상태를 관리합니다. 재고·구매 기능은 준비 중입니다.' : operations ? '판매 정지·복구와 사유·이력을 관리합니다.' : storeEditor ? '본인 매대의 소개·공지·공개 정보를 관리합니다.' : sellerReview ? '판매 신청의 승인·반려와 처리 이력을 확인할 수 있습니다.' : workspace.path === '/account' ? '이 업무의 실제 데이터 연결은 준비 중입니다.'
            : '공간 접근 권한을 확인했습니다. 실제 업무 기능과 데이터 연결은 준비 중입니다.'}</p>
        </div>}
        <Outlet />
      </div>
    </div>
  )
}
