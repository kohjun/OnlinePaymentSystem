import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { describe, expect, it, vi } from 'vitest';
import { EventCard } from './EventCard';
import type { MarketplaceEvent } from './types';

const event: MarketplaceEvent = {
  saleEventId: 'EVT-1',
  listingId: 'LIST-1',
  sellerId: 'SELLER-1',
  sellerName: '검증 판매자',
  sellerVerificationStatus: 'VERIFIED',
  productId: 'PROD-1',
  title: '한정판 스니커즈',
  description: '상품 설명',
  digitalTicket: false,
  saleType: 'FIXED_PRICE',
  status: 'LIVE',
  price: 239000,
  currency: 'KRW',
  totalQuantity: 10,
  availableQuantity: 7
};

describe('찜하기 토글', () => {
  it('찜하지 않은 상품은 담기 동작을 제공한다', async () => {
    const onToggleWishlist = vi.fn();
    const onOpen = vi.fn();
    const { container } = render(
      <EventCard event={event} onOpen={onOpen} wishlisted={false} onToggleWishlist={onToggleWishlist} />
    );

    const toggle = screen.getByRole('button', { name: '한정판 스니커즈 찜하기' });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(toggle);

    expect(onToggleWishlist).toHaveBeenCalledTimes(1);
    // 하트를 눌렀는데 상세 화면까지 열리면 안 된다.
    expect(onOpen).not.toHaveBeenCalled();
    expect((await axe(container)).violations).toEqual([]);
  });

  it('이미 찜한 상품은 해제 동작을 제공한다', () => {
    const onToggleWishlist = vi.fn();
    render(<EventCard event={event} onOpen={vi.fn()} wishlisted onToggleWishlist={onToggleWishlist} />);

    const toggle = screen.getByRole('button', { name: '한정판 스니커즈 찜 해제' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(toggle);
    expect(onToggleWishlist).toHaveBeenCalledTimes(1);
  });

  it('찜 기능을 쓰지 않는 화면에서는 하트를 그리지 않는다', () => {
    render(<EventCard event={event} onOpen={vi.fn()} />);

    expect(screen.queryByRole('button', { name: /찜/ })).toBeNull();
    expect(screen.getByRole('button', { name: '한정판 스니커즈 상세 보기' })).toBeInTheDocument();
  });
});
