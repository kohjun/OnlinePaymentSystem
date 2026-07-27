import { Clock3, Gavel, Heart, Sparkles, Ticket, Zap } from 'lucide-react';
import type { MarketplaceEvent } from './types';

const saleLabels = {
  FIXED_PRICE: { label: '일반 판매', Icon: Zap },
  DROP: { label: '한정 수량', Icon: Sparkles },
  RAFFLE: { label: '래플', Icon: Ticket },
  AUCTION: { label: '실시간 경매', Icon: Gavel }
} as const;

interface EventCardProps {
  event: MarketplaceEvent;
  onOpen: () => void;
  wishlisted?: boolean;
  onToggleWishlist?: () => void;
}

export function EventCard({ event, onOpen, wishlisted, onToggleWishlist }: EventCardProps) {
  const base = saleLabels[event.saleType];
  const label = event.digitalTicket ? '티켓 예매' : event.saleType === 'DROP' ? '선착순 한정 판매' : base.label;
  const Icon = event.digitalTicket ? Ticket : base.Icon;
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
          <Heart size={16} fill={wishlisted ? 'currentColor' : 'none'} />
        </button>
      )}
      <button className="event-card__open" type="button" onClick={onOpen} aria-label={`${event.title} 상세 보기`}>
        <div className="event-card__media">
          {event.imageUrl
            ? <img src={event.imageUrl} alt="" loading="lazy" />
            : <div className="event-card__placeholder" aria-hidden="true"><Icon size={34} /></div>}
          <span className={`sale-badge sale-badge--${event.saleType.toLowerCase()}`}><Icon size={14} />{label}</span>
        </div>
        <div className="event-card__body">
          <div className="seller-line">{event.sellerVerificationStatus === 'VERIFIED' && <span className="verified-dot" />}{event.sellerName}</div>
          <h2>{event.title}</h2>
          <div className="event-card__meta">
            <strong>{formatMoney(event.price)}</strong>
            <span>재고 {event.availableQuantity.toLocaleString('ko-KR')}</span>
          </div>
          <div className="event-card__time"><Clock3 size={14} />{timeLabel(event)}</div>
        </div>
      </button>
    </article>
  );
}

export function formatMoney(value: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(value);
}

function timeLabel(event: MarketplaceEvent) {
  if (event.status === 'ENDED') return '판매 종료';
  const target = event.status === 'SCHEDULED' ? event.startsAt : event.endsAt;
  if (!target) return event.status === 'LIVE' ? '진행 중' : '일정 확인 중';
  const date = new Date(target);
  return `${event.status === 'SCHEDULED' ? '시작' : '마감'} ${new Intl.DateTimeFormat('ko-KR', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  }).format(date)}`;
}
