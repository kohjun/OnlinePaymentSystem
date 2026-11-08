import { BadgeCheck, Heart, ImageOff } from 'lucide-react';
import type { MarketplaceEvent } from './types';

interface EventCardProps {
  event: MarketplaceEvent;
  onOpen: () => void;
  wishlisted?: boolean;
  onToggleWishlist?: () => void;
}

export function EventCard({ event, onOpen, wishlisted, onToggleWishlist }: EventCardProps) {
  const badge = badgeLabel(event);
  const soldOut = event.status === 'LIVE' && event.saleType !== 'AUCTION' && event.availableQuantity <= 0;
  const meta = metaLabel(event);
  return (
    <article className="event-card">
      {onToggleWishlist && (
        // 상세 보기 버튼 바깥에 둔다. 안에 중첩하면 하트를 누를 때
        // 상세 화면이 함께 열린다.
        <button
          className={`wishlist-toggle${wishlisted ? ' wishlist-toggle--on' : ''}`}
          type="button"
          onClick={onToggleWishlist}
          aria-pressed={wishlisted ?? false}
          aria-label={`${event.title} ${wishlisted ? '찜 해제' : '찜하기'}`}
        >
          <Heart size={18} fill={wishlisted ? 'currentColor' : 'none'} />
        </button>
      )}
      <button className="event-card__open" type="button" onClick={onOpen} aria-label={`${event.title} 상세 보기`}>
        <div className="event-card__media">
          {event.imageUrl
            ? <img src={event.imageUrl} alt="" loading="lazy" />
            : <div className="event-card__placeholder" aria-hidden="true"><ImageOff size={28} strokeWidth={1.4} /></div>}
          {badge && <span className={`sale-badge sale-badge--${event.saleType.toLowerCase()}`}>{badge}</span>}
          {soldOut && <span className="event-card__soldout">SOLD OUT</span>}
        </div>
        <div className="event-card__body">
          <div className="seller-line">
            {event.sellerVerificationStatus === 'VERIFIED' && <BadgeCheck aria-hidden="true" />}
            <span>{event.sellerName}</span>
          </div>
          <h2>{event.title}</h2>
          <strong className="event-card__price">
            {formatMoney(event.price)}
            {event.saleType === 'AUCTION' && <small>시작가</small>}
          </strong>
          {meta && <div className={`event-card__meta${meta.urgent ? ' event-card__meta--urgent' : ''}`}>{meta.text}</div>}
        </div>
      </button>
    </article>
  );
}

export function formatMoney(value: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(value);
}

function badgeLabel(event: MarketplaceEvent) {
  if (event.digitalTicket) return '티켓';
  switch (event.saleType) {
    case 'DROP': return `한정 ${event.totalQuantity.toLocaleString('ko-KR')}개`;
    case 'RAFFLE': return '래플';
    case 'AUCTION': return '경매';
    default: return undefined;
  }
}

/**
 * 카드 아래 한 줄. 일반 판매는 재고가 적을 때만 보여 주고,
 * 기간이 의미 있는 판매(한정·래플·경매)는 마감까지 남은 시간을 보여 준다.
 */
function metaLabel(event: MarketplaceEvent): { text: string; urgent: boolean } | undefined {
  if (event.status === 'ENDED') return { text: '판매 종료', urgent: false };
  if (event.status === 'ACTIVATION_FAILED') return { text: '판매 준비 중', urgent: false };
  if (event.status === 'SCHEDULED') return event.startsAt ? { text: `${shortDate(event.startsAt)} 오픈`, urgent: false } : undefined;
  if (event.saleType === 'FIXED_PRICE') {
    return event.availableQuantity > 0 && event.availableQuantity <= 3
      ? { text: `남은 수량 ${event.availableQuantity}개`, urgent: true }
      : undefined;
  }
  if (!event.endsAt) return undefined;
  const label = event.saleType === 'RAFFLE' ? '응모 마감' : event.saleType === 'AUCTION' ? '경매 마감' : '판매 마감';
  const remaining = new Date(event.endsAt).getTime() - Date.now();
  if (remaining <= 0) return { text: `${label}`, urgent: false };
  const hours = Math.floor(remaining / 3_600_000);
  if (hours < 24) return { text: `${label}까지 ${Math.max(hours, 1)}시간`, urgent: true };
  return { text: `${label} D-${Math.ceil(remaining / 86_400_000)}`, urgent: false };
}

function shortDate(value: string) {
  const date = new Date(value);
  return `${date.getMonth() + 1}.${String(date.getDate()).padStart(2, '0')}`;
}
