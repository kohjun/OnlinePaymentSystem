import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { TicketPanel } from './TicketPanel';
import { api } from './api';
import type { MarketplaceEvent } from './types';

vi.mock('./api', () => ({
  api: {
    joinQueue: vi.fn(),
    queueStatus: vi.fn(),
    ticketSeats: vi.fn(),
    holdTicketSeat: vi.fn(),
    releaseTicketSeat: vi.fn()
  }
}));

const event: MarketplaceEvent = {
  saleEventId: 'EVT-TICKET-1',
  listingId: 'LIST-1',
  sellerId: 'SELLER-1',
  sellerName: '공연 주최사',
  sellerVerificationStatus: 'VERIFIED',
  productId: 'PROD-1',
  title: '단독 공연',
  description: '좌석 지정 공연',
  digitalTicket: true,
  saleType: 'FIXED_PRICE',
  status: 'LIVE',
  price: 88000,
  currency: 'KRW',
  totalQuantity: 3,
  availableQuantity: 3
};

function seat(seatId: string, seatNumber: number, status: 'AVAILABLE' | 'HELD' | 'SOLD') {
  return {
    seatId,
    section: 'FLOOR',
    rowLabel: 'A',
    seatNumber,
    label: `A열 ${seatNumber}번`,
    status,
    ownedByCurrentUser: false
  };
}

const seatMap = {
  saleEventId: 'EVT-TICKET-1',
  eventStatus: 'LIVE',
  totalCount: 3,
  availableCount: 2,
  heldCount: 0,
  soldCount: 1,
  holdSeconds: 600,
  seats: [seat('A1', 1, 'AVAILABLE'), seat('A2', 2, 'AVAILABLE'), seat('A3', 3, 'SOLD')]
};

describe('TicketPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('holds the queue while the buyer is still waiting', async () => {
    vi.mocked(api.joinQueue).mockResolvedValue({ status: 'WAITING', rank: 42 } as never);

    render(<TicketPanel event={event} checkoutBusy={false} checkout={vi.fn()} notify={vi.fn()} />);

    expect(await screen.findByText('접속 대기 중입니다')).toBeInTheDocument();
    expect(screen.getByText(/42번/)).toBeInTheDocument();
    // 대기 중에는 좌석을 먼저 조회하지 않는다.
    expect(api.ticketSeats).not.toHaveBeenCalled();
  });

  it('holds exactly one seat when the buyer picks it', async () => {
    vi.mocked(api.joinQueue).mockResolvedValue({ status: 'ACTIVE', rank: 0 } as never);
    vi.mocked(api.queueStatus).mockResolvedValue({ status: 'ACTIVE', rank: 0 } as never);
    vi.mocked(api.ticketSeats).mockResolvedValue(seatMap as never);
    vi.mocked(api.holdTicketSeat).mockResolvedValue({} as never);
    const notify = vi.fn();

    render(<TicketPanel event={event} checkoutBusy={false} checkout={vi.fn()} notify={notify} />);

    fireEvent.click(await screen.findByRole('gridcell', { name: /A열 1번/ }));

    await waitFor(() => expect(api.holdTicketSeat).toHaveBeenCalledWith('EVT-TICKET-1', 'A1'));
    // 좌석을 바꾼 게 아니므로 해제는 호출되지 않아야 한다.
    expect(api.releaseTicketSeat).not.toHaveBeenCalled();
  });

  it('re-reads the seat map when a hold fails instead of claiming the seat', async () => {
    vi.mocked(api.joinQueue).mockResolvedValue({ status: 'ACTIVE', rank: 0 } as never);
    vi.mocked(api.queueStatus).mockResolvedValue({ status: 'ACTIVE', rank: 0 } as never);
    vi.mocked(api.ticketSeats).mockResolvedValue(seatMap as never);
    vi.mocked(api.holdTicketSeat).mockImplementation(
      () => Promise.reject(new Error('이미 선점된 좌석입니다.'))
    );

    const notify = vi.fn();
    render(<TicketPanel event={event} checkoutBusy={false} checkout={vi.fn()} notify={notify} />);

    const seatsBefore = vi.mocked(api.ticketSeats).mock.calls.length;
    fireEvent.click(await screen.findByRole('gridcell', { name: /A열 1번/ }));

    await waitFor(() => expect(api.holdTicketSeat).toHaveBeenCalled());
    await waitFor(() => expect(notify).toHaveBeenCalledWith('이미 선점된 좌석입니다.', 'error'));
    // 선점이 거절되면 화면을 서버 상태로 되맞춘다. 이걸 안 하면 다른
    // 고객이 가져간 좌석을 내 것처럼 보여주게 된다.
    await waitFor(() =>
      expect(vi.mocked(api.ticketSeats).mock.calls.length).toBeGreaterThan(seatsBefore)
    );

    // 실패한 선점은 결제 버튼을 열어주지 않아야 한다.
    expect(screen.getByRole('button', { name: /선택 좌석 Toss 결제/ })).toBeDisabled();
  });
});
