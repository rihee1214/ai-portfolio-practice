import { NavLink } from 'react-router'

export function AuthEntryLinks() {
  return <>
    <NavLink className="navigation-link navigation-link--primary" to="/login">로그인</NavLink>
    <NavLink className="navigation-link" to="/signup">회원가입</NavLink>
  </>
}

export function AuthEntryActions() {
  return <div className="auth-entry-actions"><AuthEntryLinks /></div>
}
