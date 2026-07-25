import { render, screen } from '@testing-library/react';
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
  saleType: 'RAFFLE',
  status: 'LIVE',
  price: 239000,
  currency: 'KRW',
  totalQuantity: 10,
  availableQuantity: 7
};

describe('EventCard', () => {
  it('exposes the marketplace item as one accessible action', async () => {
    const onOpen = vi.fn();
    const { container } = render(<EventCard event={event} onOpen={onOpen} />);

    expect(screen.getByRole('button', { name: '한정판 스니커즈 상세 보기' })).toBeInTheDocument();
    expect(screen.getByText('검증 판매자')).toBeInTheDocument();
    expect((await axe(container)).violations).toEqual([]);
  });
});
