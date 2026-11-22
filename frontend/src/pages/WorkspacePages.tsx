import { Link } from 'react-router'
import type { Feature, Workspace } from '../navigation'
import { useWorkspaceFeatures, useWorkspaceMenu } from '../auth/WorkspaceMenuContext'

export function WorkspaceOverview({ workspace }: { workspace: Workspace }) {
  const features = useWorkspaceFeatures(workspace)
  const { buyer } = useWorkspaceMenu()
  return (
    <section className="workspace-page">
      <p className="eyebrow">{workspace.label} 공간</p>
      <h1>{workspace.title}</h1>
      <p className="page-description">{workspace.description}</p>
      <div className="feature-grid">
        {features.map((feature) => (
          <Link className="feature-card" key={feature.path} to={`${workspace.path}/${feature.path}`}>
            <h2>{feature.title} <span aria-hidden="true">↗</span></h2>
            <p>{feature.description}</p>
          </Link>
        ))}
      </div>
      {workspace.path === '/seller' && buyer && <p className="workspace-hint"><Link to="/account">내 계정</Link></p>}
    </section>
  )
}

export function FeaturePage({ feature }: { feature: Feature }) {
  return (
    <section className="workspace-page">
      <p className="eyebrow">구현 예정</p>
      <h1>{feature.title}</h1>
      <p className="page-description">{feature.description}</p>
      <div className="empty-panel">
        <span className="empty-symbol" aria-hidden="true">＋</span>
        <h2>이 공간을 준비하고 있어요</h2>
        <p>{feature.planned}</p>
        <p className="empty-caption">아직 데이터를 조회하지 않았습니다.</p>
      </div>
    </section>
  )
}
