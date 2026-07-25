import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { SellerOrders, SellerSettlements } from './SellerOperations';

vi.mock('./api', () => ({
  api: {
    sellerOrders: vi.fn(),
    updateFulfillment: vi.fn(),
    sellerPayoutAccount: vi.fn(),
    sellerPayouts: vi.fn(),
    submitSellerPayoutAccount: vi.fn()
  }
}));

describe('Seller operations', () => {
  it('moves a paid order into fulfillment processing', async () => {
    vi.mocked(api.sellerOrders).mockResolvedValue([{
      marketplaceOrderId: 'MORD-1', sellerId: 'SELLER-1', customerId: 'CUST-2',
      productId: 'PROD-1', amount: 55000, currency: 'KRW', status: 'PAID',
      fulfillmentStatus: 'READY_TO_FULFILL', createdAt: '2026-07-18T10:00:00'
    }]);
    vi.mocked(api.updateFulfillment).mockResolvedValue({
      marketplaceOrderId: 'MORD-1', sellerId: 'SELLER-1', amount: 55000, currency: 'KRW',
      status: 'PAID', fulfillmentStatus: 'PROCESSING', createdAt: '2026-07-18T10:00:00'
    });

    render(<SellerOrders sellerId="SELLER-1" notify={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: '포장 시작' }));

    expect(api.updateFulfillment).toHaveBeenCalledWith('SELLER-1', 'MORD-1', 'PROCESSING', undefined, undefined);
  });

  it('renders the empty payout account and ledger states', async () => {
    vi.mocked(api.sellerPayoutAccount).mockResolvedValue(undefined);
    vi.mocked(api.sellerPayouts).mockResolvedValue([]);

    render(<SellerSettlements sellerId="SELLER-1" notify={vi.fn()} />);

    expect(await screen.findByText('정산 계좌 미등록')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '계좌 등록' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('정산 내역이 없습니다')).toBeInTheDocument());
  });
});
