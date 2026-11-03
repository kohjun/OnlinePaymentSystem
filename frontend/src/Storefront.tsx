import { ArrowRight, BadgeCheck, PackageSearch, Scale, Wallet } from 'lucide-react';
import type { SaleType } from './types';

export const categories: Array<{ value: string; label: string }> = [
  { value: '', label: '전체' },
  { value: 'FASHION', label: '패션' },
  { value: 'ELECTRONICS', label: '테크' },
  { value: 'HOME_LIVING', label: '홈·리빙' },
  { value: 'COLLECTIBLE', label: '컬렉터블' },
  { value: 'BEAUTY', label: '뷰티' },
  { value: 'CONCERT', label: '공연·티켓' }
];

export function categoryLabel(value: string) {
  return categories.find(category => category.value === value)?.label ?? '전체';
}

export function StoreHero({ onCategory, onSaleType }: {
  onCategory: (category: string) => void;
  onSaleType: (saleType: SaleType) => void;
}) {
  const month = new Date().getMonth() + 1;
  return (
    <section className="store-hero" aria-label="기획전">
      <button type="button" className="hero-tile hero-tile--main" onClick={() => onCategory('FASHION')}>
        <img src="/catalog/banner-wardrobe.jpg" alt="" />
        <span className="hero-tile__copy">
          <span>{month}월 셀렉션</span>
          <strong>계절이 바뀌는 옷장</strong>
          <em>검수를 마친 니트, 코트, 부츠를 모았습니다</em>
          <span className="hero-tile__cta">패션 보러 가기<ArrowRight aria-hidden="true" /></span>
        </span>
      </button>
      <div className="store-hero__side">
        <button type="button" className="hero-tile hero-tile--side" onClick={() => onCategory('HOME_LIVING')}>
          <img src="/catalog/banner-living.jpg" alt="" />
          <span className="hero-tile__copy">
            <span>HOME &amp; LIVING</span>
            <strong>오래 머무는 공간을 위한 오브제</strong>
          </span>
        </button>
        <button type="button" className="hero-tile hero-tile--side" onClick={() => onSaleType('AUCTION')}>
          <img src="/catalog/banner-objects.jpg" alt="" />
          <span className="hero-tile__copy">
            <span>LIVE AUCTION</span>
            <strong>빈티지 컬렉션 실시간 경매</strong>
          </span>
        </button>
      </div>
    </section>
  );
}

export function TrustBar() {
  return (
    <section className="trust-bar" aria-label="거래 안내">
      <div><PackageSearch aria-hidden="true" /><div><strong>등록 상품 검수</strong><span>운영팀 검수를 통과한 상품만 판매합니다</span></div></div>
      <div><BadgeCheck aria-hidden="true" /><div><strong>토스페이먼츠 안전결제</strong><span>결제 정보는 에브리세일에 남지 않습니다</span></div></div>
      <div><Wallet aria-hidden="true" /><div><strong>구매 확정 후 정산</strong><span>판매 대금은 구매 확정 뒤 지급됩니다</span></div></div>
      <div><Scale aria-hidden="true" /><div><strong>분쟁 조정 지원</strong><span>문제가 생기면 운영팀이 함께 확인합니다</span></div></div>
    </section>
  );
}

export function SiteFooter({ onSell }: { onSell: () => void }) {
  return (
    <footer className="site-footer">
      <div className="site-footer__inner">
        <div className="site-footer__top">
          <div className="site-footer__brand">
            <strong>EVERYSALE</strong>
            <p>검수를 거친 상품만 거래되는 C2C 마켓플레이스.<br />일반 판매부터 한정 수량, 래플, 실시간 경매까지 한곳에서.</p>
          </div>
          <div>
            <h3>고객센터</h3>
            <ul><li>평일 10:00 – 18:00</li><li>점심시간 12:30 – 13:30</li><li>주말·공휴일 휴무</li></ul>
          </div>
          <div>
            <h3>판매자</h3>
            <ul>
              <li><button type="button" className="text-button" onClick={onSell}>판매 시작하기</button></li>
              <li>판매 수수료 10%</li>
              <li>구매 확정 후 정산</li>
            </ul>
          </div>
          <div>
            <h3>안내</h3>
            <ul><li>이용약관</li><li>개인정보처리방침</li><li>검수 기준</li></ul>
          </div>
        </div>
        <div className="site-footer__legal">
          <p>(주)에브리세일 · 사업자등록번호 000-00-00000 · 통신판매업 신고 제0000-서울-0000호</p>
          <p>에브리세일은 통신판매중개자로서 거래 당사자가 아니며, 판매자가 등록한 상품 정보와 거래에 대한 책임은 판매자에게 있습니다.</p>
          <p>© {new Date().getFullYear()} EVERYSALE</p>
        </div>
      </div>
    </footer>
  );
}
