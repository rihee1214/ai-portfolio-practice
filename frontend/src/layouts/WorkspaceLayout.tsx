import { NavLink, Outlet } from 'react-router'
import type { Workspace } from '../navigation'

export function WorkspaceLayout({ workspace }: { workspace: Workspace }) {
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
        <div className="preview-notice">
          <strong>화면 구조 미리보기</strong>
          <p>이 업무 화면의 권한 확인과 실제 데이터 연결은 아직 구현되지 않았습니다. 누구나 미리보기 화면 틀을 둘러볼 수 있습니다.</p>
        </div>
        <Outlet />
      </div>
    </div>
  )
}
