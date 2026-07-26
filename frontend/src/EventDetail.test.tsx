import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { EventDetail } from './EventDetail';
import { api } from './api';
import type { Identity, MarketplaceEvent } from './types';

vi.mock('./api', () => ({
  ApiError: class extends Error {},
  api: {
    listSellerReviews: vi.fn(),
    reportListing: vi.fn(),
    auctionStatus: vi.fn(),
    raffleStatus: vi.fn(),
    createCheckoutIntent: vi.fn()
  },
  openTossPayment: vi.fn()
}));

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

const identity: Identity = { customerId: 'CUST-1', userId: 'USER-1', roles: ['CUSTOMER'] } as Identity;

function renderDetail() {
  return render(
    <EventDetail event={event} identity={identity} onClose={vi.fn()} notify={vi.fn()} />
  );
}

describe('EventDetail seller reputation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the seller rating so a buyer can judge before paying', async () => {
    vi.mocked(api.listSellerReviews).mockResolvedValue([
      { reviewId: 'REV-1', marketplaceOrderId: 'MORD-1', reviewerCustomerId: 'CUST-9', targetSellerId: 'SELLER-1', rating: 5, comment: '배송이 빨랐습니다.' },
      { reviewId: 'REV-2', marketplaceOrderId: 'MORD-2', reviewerCustomerId: 'CUST-8', targetSellerId: 'SELLER-1', rating: 4 }
    ] as never);

    const { container } = renderDetail();

    expect(await screen.findByRole('heading', { name: '판매자 평가' })).toBeInTheDocument();
    expect(await screen.findByText('4.5')).toBeInTheDocument();
    expect(screen.getByText(/구매자 평가 2건/)).toBeInTheDocument();
    expect(screen.getByText('배송이 빨랐습니다.')).toBeInTheDocument();
    expect(api.listSellerReviews).toHaveBeenCalledWith('SELLER-1');
    expect((await axe(container)).violations).toEqual([]);
  });

  it('states plainly that a seller has no reviews yet', async () => {
    vi.mocked(api.listSellerReviews).mockResolvedValue([] as never);

    renderDetail();

    expect(await screen.findByText('아직 등록된 구매자 평가가 없습니다.')).toBeInTheDocument();
  });

  it('keeps the purchase flow usable when the review lookup fails', async () => {
    vi.mocked(api.listSellerReviews).mockRejectedValue(new Error('reviews unavailable'));

    renderDetail();

    // 평판 조회 실패가 결제 동선을 막아서는 안 된다.
    expect(await screen.findByText('아직 등록된 구매자 평가가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Toss로 안전하게 결제/ })).toBeEnabled();
  });

  it('submits a listing report through the report dialog', async () => {
    vi.mocked(api.listSellerReviews).mockResolvedValue([] as never);
    vi.mocked(api.reportListing).mockResolvedValue({} as never);

    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /상품 신고/ }));

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  });
});
