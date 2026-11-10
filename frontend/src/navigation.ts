export type Feature = {
  path: string
  title: string
  description: string
  planned: string
}

export type Workspace = {
  path: string
  label: string
  title: string
  description: string
  features: Feature[]
}

export const workspaces: Workspace[] = [
  {
    path: '/account', label: '구매자', title: '나의 쇼핑',
    description: '주문부터 배송까지, 나의 쇼핑을 한곳에서 확인할 공간입니다.',
    features: [
      { path: 'orders', title: '주문·배송', description: '주문과 결제 이력, 배송 진행을 확인합니다.', planned: '회원의 주문과 기본 배송 조회를 연결할 예정입니다. 비회원 조회는 별도 인증 화면에서 제공할 예정입니다.' },
      { path: 'addresses', title: '배송지', description: '자주 사용하는 배송지를 관리합니다.', planned: '본인 배송지 등록·수정·삭제를 연결할 예정입니다.' },
      { path: 'wishlist', title: '찜한 상품', description: '마음에 드는 상품을 다시 찾아봅니다.', planned: '회원별 찜 목록과 상품 정보를 연결할 예정입니다.' },
      { path: 'coupons', title: '내 쿠폰', description: '보유한 쿠폰과 사용 조건을 확인합니다.', planned: '회원 쿠폰을 연결할 예정입니다. 비회원은 공개 이벤트만 이용할 수 있습니다.' },
    ],
  },
  {
    path: '/seller', label: '판매자', title: '매대 운영',
    description: '나만의 매대를 가꾸고, 상품과 주문을 관리할 공간입니다.',
    features: [
      { path: 'store', title: '내 매대', description: '매대의 소개와 공개 정보를 관리합니다.', planned: '판매 신청·승인 후 본인 매대를 관리하도록 연결할 예정입니다.' },
      { path: 'coupons', title: '회원 쿠폰', description: '쿠폰 조건과 발급 한도를 관리합니다.', planned: '회원 쿠폰 발급·조건 관리' },
      { path: 'promotions', title: '공개 행사', description: '상품·배송 행사를 관리합니다.', planned: '회원·비회원 동일 공개 행사' },
      { path: 'products', title: '상품 관리', description: '상품과 옵션, 이미지, 재고를 관리합니다.', planned: '본인 매대의 상품 등록·수정·판매 중지를 연결할 예정입니다.' },
      { path: 'orders', title: '판매 주문', description: '본인 매대의 주문을 확인하고 모의 배송을 발송합니다.', planned: '실제 저장된 주문과 모의 배송 진행 조회' },
      { path: 'settlements', title: '매출·모의 정산', description: '매출과 정산 내역을 확인합니다.', planned: '모의 정산 조회를 연결할 예정이며 실제 송금은 제공하지 않습니다.' },
    ],
  },
  {
    path: '/admin', label: '운영자', title: '사이트 운영',
    description: '회원과 판매 활동을 살피고, 신고와 거래 문제를 관리할 공간입니다.',
    features: [
      { path: 'users', title: '회원 관리', description: '회원 상태와 조치 이력을 관리합니다.', planned: '운영 권한 확인 후 회원 정지·복원과 사유 기록을 연결할 예정입니다.' },
      { path: 'sellers', title: '판매자 심사', description: '판매 신청과 매대 운영 상태를 확인합니다.', planned: '판매 신청 승인·반려와 처리 이력을 연결할 예정입니다.' },
      { path: 'stores', title: '매대 운영 관리', description: '판매 정지·복구와 조치 이력을 확인합니다.', planned: '매대 운영 조치를 관리합니다.' },
      { path: 'moderation', title: '콘텐츠 조치', description: '문제 상품·리뷰·댓글을 관리합니다.', planned: '사유와 이력을 남기는 비공개·복원을 연결할 예정입니다.' },
      { path: 'catalog', title: '카테고리 운영', description: '카테고리 이름과 활성 상태를 관리합니다.', planned: '카테고리 변경 이력을 보존합니다.' },
      { path: 'refunds', title: '환불 대사', description: 'SYSTEM 권한으로 환불 관측과 조회 전용 운영 근거를 확인합니다.', planned: '현재 환불 조회와 감사되는 운영 대사' },
      { path: 'orders', title: '거래 관리', description: '주문과 거래 지원 내역을 확인합니다.', planned: '업무별 운영 권한과 거래 감사 이력을 연결할 예정입니다.' },
    ],
  },
]
