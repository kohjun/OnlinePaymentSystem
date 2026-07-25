import type {
  AdminOperationsAudit,
  AdminOperationsQueues,
  DistributionReadiness,
  AuctionStatus,
  Identity,
  InventoryInspection,
  MarketplaceEvent,
  MarketplaceOrder,
  Page,
  RaffleStatus,
  QueueStatus,
  SaleEventInput,
  SellerListing,
  SellerListingInput,
  SellerProfile,
  ShippingAddress,
  SystemHealth,
  SellerPayout,
  SellerPayoutAccount,
  SellerPayoutAccountInput,
  TicketSeatHold,
  TicketSeatMap,
  TossIntent
} from './types';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly correlationId?: string
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const correlationId = crypto.randomUUID();
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': correlationId,
      ...init?.headers
    }
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(
      body.detail ?? body.message ?? `요청을 처리하지 못했습니다 (${response.status})`,
      response.status,
      body.code ?? body.errorCode,
      body.correlationId ?? response.headers.get('X-Correlation-Id') ?? correlationId
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function optionalRequest<T>(path: string): Promise<T | undefined> {
  try { return await request<T>(path); }
  catch (error) {
    if (error instanceof ApiError && error.status === 404) return undefined;
    throw error;
  }
}

async function requestAllowing<T>(path: string, allowedStatuses: number[]): Promise<T> {
  const correlationId = crypto.randomUUID();
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': correlationId }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok && !allowedStatuses.includes(response.status)) {
    throw new ApiError(
      body.detail ?? body.message ?? `요청을 처리하지 못했습니다 (${response.status})`,
      response.status,
      body.code ?? body.errorCode,
      body.correlationId ?? response.headers.get('X-Correlation-Id') ?? correlationId
    );
  }
  return body as T;
}

export const api = {
  me: () => request<Identity>('/api/me'),
  createSellerProfile: (displayName: string) => request<SellerProfile>('/api/me/seller-profile', {
    method: 'POST', body: JSON.stringify({ displayName })
  }),
  submitSellerVerification: (evidenceRef: string, note: string) => request<SellerProfile>('/api/c2c/seller/verification', {
    method: 'POST', body: JSON.stringify({ evidenceRef, note: note || undefined })
  }),
  sellerListings: () => request<SellerListing[]>('/api/c2c/seller/listings'),
  createSellerListing: (body: SellerListingInput) => request<SellerListing>('/api/c2c/listings', {
    method: 'POST', body: JSON.stringify(body)
  }),
  updateSellerListing: (listingId: string, body: SellerListingInput) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}`,
    { method: 'PATCH', body: JSON.stringify(body) }
  ),
  submitSellerListing: (listingId: string) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/submit`, { method: 'POST' }
  ),
  createSaleEvent: (listingId: string, body: SaleEventInput) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/sale-events`,
    { method: 'POST', body: JSON.stringify(body) }
  ),
  publishSaleEvent: (eventId: string) => request<SellerListing>(
    `/api/c2c/sale-events/${encodeURIComponent(eventId)}/publish`, { method: 'POST' }
  ),
  sellerOrders: (sellerId: string) => request<MarketplaceOrder[]>(
    `/api/sellers/${encodeURIComponent(sellerId)}/orders?page=0&size=100`
  ),
  updateFulfillment: (
    sellerId: string,
    orderId: string,
    fulfillmentStatus: 'PROCESSING' | 'SHIPPED' | 'DELIVERED',
    trackingCarrier?: string,
    trackingNumber?: string
  ) => request<MarketplaceOrder>(
    `/api/sellers/${encodeURIComponent(sellerId)}/orders/${encodeURIComponent(orderId)}/fulfillment`,
    { method: 'PATCH', body: JSON.stringify({ fulfillmentStatus, trackingCarrier, trackingNumber }) }
  ),
  sellerPayouts: (sellerId: string) => request<SellerPayout[]>(`/api/sellers/${encodeURIComponent(sellerId)}/payouts`),
  sellerPayoutAccount: () => optionalRequest<SellerPayoutAccount>('/api/c2c/seller/payout-account'),
  submitSellerPayoutAccount: (body: SellerPayoutAccountInput) => request<SellerPayoutAccount>('/api/c2c/seller/payout-account', {
    method: 'POST', body: JSON.stringify(body)
  }),
  adminOperationQueues: (limit = 20) => request<AdminOperationsQueues>(`/api/admin/operations/queues?limit=${limit}`),
  adminOperationAudit: (limit = 100) => request<AdminOperationsAudit>(`/api/admin/operations/audit?limit=${limit}`),
  executeAdminOperation: (queue: string, itemId: string, action: string, note?: string) => request(
    `/api/admin/operations/queues/${encodeURIComponent(queue)}/items/${encodeURIComponent(itemId)}/actions/${encodeURIComponent(action)}`,
    { method: 'POST', body: JSON.stringify({ note: note || undefined, idempotencyKey: crypto.randomUUID() }) }
  ),
  systemHealth: () => request<SystemHealth>('/api/system/health'),
  distributionReadiness: () => requestAllowing<DistributionReadiness>('/api/system/readiness', [503]),
  inspectInventoryReconciliation: () => requestAllowing<InventoryInspection>('/api/system/inventory/reconcile', [400]),
  repairInventoryReconciliation: () => request<{ status: string; message: string }>('/api/system/inventory/reconcile', { method: 'POST' }),
  events: (params: URLSearchParams) => request<Page<MarketplaceEvent>>(`/api/marketplace/events/page?${params}`),
  event: (eventId: string) => request<MarketplaceEvent>(`/api/marketplace/events/${encodeURIComponent(eventId)}`),
  auctionStatus: (eventId: string) => request<AuctionStatus>(`/api/marketplace/events/${encodeURIComponent(eventId)}/auction/status`),
  auctionMe: (eventId: string) => request<AuctionStatus>(`/api/marketplace/events/${encodeURIComponent(eventId)}/auction/me`),
  bid: (eventId: string, bidAmount: number) => request(`/api/marketplace/events/${encodeURIComponent(eventId)}/bids`, {
    method: 'POST',
    body: JSON.stringify({ bidAmount, idempotencyKey: crypto.randomUUID() })
  }),
  raffleStatus: (eventId: string, customerId: string) => request<RaffleStatus>(
    `/api/marketplace/events/${encodeURIComponent(eventId)}/raffle/status?customerId=${encodeURIComponent(customerId)}`
  ),
  enterRaffle: (eventId: string) => request(`/api/marketplace/events/${encodeURIComponent(eventId)}/raffle/entries`, {
    method: 'POST',
    body: JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  }),
  joinQueue: () => request<QueueStatus>('/api/queue/join', { method: 'POST' }),
  queueStatus: () => request<QueueStatus>('/api/queue/status'),
  ticketSeats: (eventId: string) => request<TicketSeatMap>(
    `/api/marketplace/events/${encodeURIComponent(eventId)}/tickets/seats`
  ),
  holdTicketSeat: (eventId: string, seatId: string) => request<TicketSeatHold>(
    `/api/marketplace/events/${encodeURIComponent(eventId)}/tickets/seats/${encodeURIComponent(seatId)}/hold`,
    { method: 'POST' }
  ),
  releaseTicketSeat: (eventId: string, seatId: string) => request<void>(
    `/api/marketplace/events/${encodeURIComponent(eventId)}/tickets/seats/${encodeURIComponent(seatId)}/hold`,
    { method: 'DELETE' }
  ),
  orders: (customerId: string) => request<MarketplaceOrder[]>(
    `/api/marketplace/customers/${encodeURIComponent(customerId)}/orders?page=0&size=50`
  ),
  addresses: () => request<ShippingAddress[]>('/api/c2c/addresses'),
  createAddress: (body: Omit<ShippingAddress, 'addressId'>) => request<ShippingAddress>('/api/c2c/addresses', {
    method: 'POST', body: JSON.stringify(body)
  }),
  setDefaultAddress: (addressId: string) => request<ShippingAddress>(`/api/c2c/addresses/${encodeURIComponent(addressId)}/default`, { method: 'PATCH' }),
  deleteAddress: (addressId: string) => request<void>(`/api/c2c/addresses/${encodeURIComponent(addressId)}`, { method: 'DELETE' }),
  confirmDelivery: (orderId: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/confirm-delivery`, { method: 'POST' }),
  openDispute: (orderId: string, reason: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/dispute`, {
    method: 'POST', body: JSON.stringify({ reason })
  }),
  reviewSeller: (orderId: string, rating: number, comment: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/seller-review`, {
    method: 'POST', body: JSON.stringify({ rating, comment })
  }),
  reportListing: (listingId: string, reason: string, details: string) => request('/api/c2c/reports', {
    method: 'POST', body: JSON.stringify({ targetType: 'LISTING', targetId: listingId, reason, details })
  }),
  createIntent: (
    eventId: string,
    checkoutType: 'DIRECT' | 'RAFFLE_WINNER' | 'AUCTION_WINNER',
    amount: number,
    options?: { seatId?: string; shippingAddressId?: string }
  ) => {
    const suffix = checkoutType === 'DIRECT'
      ? 'checkout/toss/intents'
      : checkoutType === 'RAFFLE_WINNER'
        ? 'raffle/winner-checkout/toss/intents'
        : 'auction/winner-checkout/toss/intents';
    const baseUrl = `${window.location.origin}/app/`;
    return request<TossIntent>(`/api/marketplace/events/${encodeURIComponent(eventId)}/${suffix}`, {
      method: 'POST',
      body: JSON.stringify({
        quantity: 1,
        idempotencyKey: crypto.randomUUID(),
        clientId: 'everysale-marketplace-web',
        seatId: options?.seatId,
        shippingInfo: options?.shippingAddressId ? { addressId: options.shippingAddressId } : undefined,
        paymentInfo: {
          amount,
          currency: 'KRW',
          paymentMethod: 'CREDIT_CARD',
          successUrl: `${baseUrl}?tossResult=success`,
          failUrl: `${baseUrl}?tossResult=fail`
        }
      })
    });
  },
  confirmToss: (payload: { intentId: string; paymentKey: string; orderId: string; amount: number }) =>
    request<{ status: string; message?: string; workflowId?: string }>('/api/payments/toss/confirm', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  cancelToss: (intentId: string) => request<TossIntent>(
    `/api/payments/toss/intents/${encodeURIComponent(intentId)}/cancel`,
    { method: 'POST' }
  )
};

declare global {
  interface Window {
    TossPayments?: (clientKey: string) => {
      payment: (options: { customerKey: string }) => {
        requestPayment: (options: Record<string, unknown>) => Promise<void>;
      };
    };
    electronAPI?: {
      minimizeWindow: () => void;
      maximizeWindow: () => void;
      closeWindow: () => void;
      onMaximizeChange: (callback: (maximized: boolean) => void) => void;
    };
  }
}

export async function openTossPayment(intent: TossIntent, customerName: string) {
  if (!window.TossPayments) throw new ApiError('Toss Payments SDK를 불러오지 못했습니다.', 503, 'TOSS_SDK_UNAVAILABLE');
  const toss = window.TossPayments(intent.clientKey);
  const payment = toss.payment({ customerKey: intent.customerKey });
  await payment.requestPayment({
    method: 'CARD',
    amount: { currency: intent.currency, value: Number(intent.amount) },
    orderId: intent.orderId,
    orderName: intent.orderName,
    successUrl: new URL(intent.successUrl, window.location.origin).toString(),
    failUrl: new URL(intent.failUrl, window.location.origin).toString(),
    customerName
  });
}
