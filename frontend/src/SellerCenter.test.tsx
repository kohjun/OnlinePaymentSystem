import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { describe, expect, it, vi } from 'vitest';
import { SellerCenter } from './SellerCenter';
import { api } from './api';
import type { Identity } from './types';

vi.mock('./api', () => ({
  api: {
    me: vi.fn(),
    sellerListings: vi.fn().mockResolvedValue([]),
    createSellerProfile: vi.fn(),
    submitSellerVerification: vi.fn(),
    createSellerListing: vi.fn(),
    updateSellerListing: vi.fn(),
    submitSellerListing: vi.fn(),
    createSaleEvent: vi.fn(),
    publishSaleEvent: vi.fn(),
    sellerOrders: vi.fn().mockResolvedValue([]),
    sellerPayoutAccount: vi.fn().mockResolvedValue(undefined),
    sellerPayouts: vi.fn().mockResolvedValue([]),
    updateFulfillment: vi.fn(),
    submitSellerPayoutAccount: vi.fn()
  }
}));

const identity: Identity = {
  userId: 'USER-1',
  customerId: 'CUST-1',
  sellerId: 'SELLER-1',
  roles: ['CUSTOMER'],
  user: { displayName: '테스트 판매자' },
  sellerProfile: {
    sellerId: 'SELLER-1',
    displayName: '테스트 상점',
    status: 'ACTIVE',
    verificationStatus: 'VERIFIED'
  }
};

describe('SellerCenter', () => {
  it('opens a complete product registration form for a verified seller', async () => {
    const { container } = render(
      <SellerCenter identity={identity} close={vi.fn()} notify={vi.fn()} onIdentityChange={vi.fn()} />
    );

    expect(await screen.findByText('등록된 상품이 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주문·배송' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '정산' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '상품 등록' }));

    expect(screen.getByRole('heading', { name: '상품 등록' })).toBeInTheDocument();
    expect(screen.getByLabelText('상품명')).toBeInTheDocument();
    expect(screen.getByLabelText('판매 기준가')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장 후 검수 제출' })).toBeDisabled();
    expect((await axe(container)).violations).toEqual([]);

    vi.mocked(api.createSellerListing).mockResolvedValue({
      listingId: 'LIST-1', sellerId: 'SELLER-1', productId: 'PROD-1', name: '빈티지 재킷',
      category: 'FASHION', itemCondition: 'GOOD', status: 'DRAFT', price: 89000,
      totalQuantity: 1, availableQuantity: 1
    });
    fireEvent.change(screen.getByLabelText('상품명'), { target: { value: '빈티지 재킷' } });
    fireEvent.change(screen.getByLabelText('판매 기준가'), { target: { value: '89000' } });
    fireEvent.click(screen.getByRole('button', { name: '초안 저장' }));

    expect(api.createSellerListing).toHaveBeenCalledWith(expect.objectContaining({
      name: '빈티지 재킷', price: 89000, quantity: 1
    }));
  });
});
