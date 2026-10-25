import type {
  AdminOperationsAudit,
  AuthToken,
  AuthRuntimeConfig,
  AdminOperationsQueues,
  DistributionReadiness,
  AuctionStatus,
  Identity,
  InventoryInspection,
  ListingMedia,
  ListingMediaType,
  MarketplaceEvent,
  MarketplaceNotification,
  NotificationInbox,
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
  SellerReview,
  WishlistItem,
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

const TOKEN_STORAGE_KEY = 'everysale.accessToken';
const OIDC_STATE_KEY = 'everysale.oidc.state';
const OIDC_VERIFIER_KEY = 'everysale.oidc.verifier';
const OIDC_REDIRECT_KEY = 'everysale.oidc.redirectUri';

/**
 * 로그인해서 받은 액세스 토큰.
 *
 * 토큰이 없으면 서버의 로컬 목 인증이 기본 계정으로 처리한다. 로그인하면
 * 그 계정으로 바뀐다. 운영에서는 목 인증이 꺼져 있어 토큰이 없으면 401이다.
 */
export const authToken = {
  get: () => {
    const current = sessionStorage.getItem(TOKEN_STORAGE_KEY);
    if (current) return current;
    const legacy = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!legacy) return undefined;
    sessionStorage.setItem(TOKEN_STORAGE_KEY, legacy);
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    return legacy;
  },
  set: (token: string) => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    localStorage.removeItem(TOKEN_STORAGE_KEY);
  },
  clear: () => {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(TOKEN_STORAGE_KEY);
  }
};

function authHeaders(): Record<string, string> {
  const token = authToken.get();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const correlationId = crypto.randomUUID();
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': correlationId,
      ...authHeaders(),
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

async function uploadRequest<T>(path: string, body: FormData): Promise<T> {
  const correlationId = crypto.randomUUID();
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-Correlation-Id': correlationId, ...authHeaders() },
    body
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new ApiError(
      payload.detail ?? payload.message ?? `파일을 업로드하지 못했습니다 (${response.status})`,
      response.status,
      payload.code ?? payload.errorCode,
      payload.correlationId ?? response.headers.get('X-Correlation-Id') ?? correlationId
    );
  }
  return response.json() as Promise<T>;
}

async function blobRequest(path: string): Promise<Blob> {
  const correlationId = crypto.randomUUID();
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'X-Correlation-Id': correlationId, ...authHeaders() }
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new ApiError(
      payload.detail ?? payload.message ?? `첨부 파일을 불러오지 못했습니다 (${response.status})`,
      response.status,
      payload.code ?? payload.errorCode,
      payload.correlationId ?? response.headers.get('X-Correlation-Id') ?? correlationId
    );
  }
  return response.blob();
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
    headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': correlationId, ...authHeaders() }
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

export async function prepareOidcAuthorizationUrl(config?: AuthRuntimeConfig): Promise<string> {
  const runtime = config ?? await request<AuthRuntimeConfig>('/api/auth/config');
  if (runtime.mode !== 'OIDC' || !runtime.authorizationUri || !runtime.tokenUri || !runtime.clientId) {
    throw new Error('외부 로그인 설정이 완료되지 않았습니다.');
  }
  const authorization = safeOidcUrl(runtime.authorizationUri, '인증');
  safeOidcUrl(runtime.tokenUri, '토큰');
  const state = randomBase64Url(24);
  const verifier = randomBase64Url(64);
  const challenge = base64Url(new Uint8Array(await crypto.subtle.digest(
    'SHA-256', new TextEncoder().encode(verifier))));
  const redirectUri = new URL('/app/', window.location.origin).toString();
  sessionStorage.setItem(OIDC_STATE_KEY, state);
  sessionStorage.setItem(OIDC_VERIFIER_KEY, verifier);
  sessionStorage.setItem(OIDC_REDIRECT_KEY, redirectUri);

  authorization.searchParams.set('response_type', 'code');
  authorization.searchParams.set('client_id', runtime.clientId);
  authorization.searchParams.set('redirect_uri', redirectUri);
  authorization.searchParams.set('scope', runtime.scopes.join(' '));
  authorization.searchParams.set('state', state);
  authorization.searchParams.set('code_challenge', challenge);
  authorization.searchParams.set('code_challenge_method', 'S256');
  return authorization.toString();
}

export async function completeOidcCallbackIfPresent(): Promise<boolean> {
  const params = new URLSearchParams(window.location.search);
  if (params.has('tossResult') || !params.get('state') || (!params.get('code') && !params.get('error'))) {
    return false;
  }
  const expectedState = sessionStorage.getItem(OIDC_STATE_KEY);
  const verifier = sessionStorage.getItem(OIDC_VERIFIER_KEY);
  const redirectUri = sessionStorage.getItem(OIDC_REDIRECT_KEY);
  try {
    if (params.get('error')) {
      throw new Error(params.get('error_description') ?? '로그인이 취소되었거나 승인되지 않았습니다.');
    }
    if (!expectedState || params.get('state') !== expectedState || !verifier || !redirectUri) {
      throw new Error('로그인 요청 검증에 실패했습니다. 다시 로그인해 주세요.');
    }
    const runtime = await request<AuthRuntimeConfig>('/api/auth/config');
    if (runtime.mode !== 'OIDC' || !runtime.tokenUri || !runtime.clientId) {
      throw new Error('외부 로그인 설정이 완료되지 않았습니다.');
    }
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: runtime.clientId,
      code: params.get('code')!,
      redirect_uri: redirectUri,
      code_verifier: verifier
    });
    const response = await fetch(safeOidcUrl(runtime.tokenUri, '토큰').toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body
    });
    const token = await response.json().catch(() => ({}));
    if (!response.ok || typeof token.access_token !== 'string' || !token.access_token) {
      throw new Error(token.error_description ?? '외부 로그인 토큰을 발급받지 못했습니다.');
    }
    authToken.set(token.access_token);
    return true;
  } finally {
    sessionStorage.removeItem(OIDC_STATE_KEY);
    sessionStorage.removeItem(OIDC_VERIFIER_KEY);
    sessionStorage.removeItem(OIDC_REDIRECT_KEY);
    window.history.replaceState({}, '', '/app/');
  }
}

function randomBase64Url(size: number): string {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function safeOidcUrl(value: string, label: string): URL {
  const url = new URL(value);
  const localDevelopment = url.protocol === 'http:'
    && (url.hostname === 'localhost' || url.hostname === '127.0.0.1');
  if (url.protocol !== 'https:' && !localDevelopment) {
    throw new Error(`${label} 서버 주소는 HTTPS여야 합니다.`);
  }
  return url;
}

export const api = {
  authConfig: () => request<AuthRuntimeConfig>('/api/auth/config'),
  me: () => request<Identity>('/api/me'),
  signUp: (email: string, password: string, displayName?: string) =>
    request<AuthToken>('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName: displayName || undefined })
    }),
  login: (email: string, password: string) =>
    request<AuthToken>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password })
    }),
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
  pauseSellerListing: (listingId: string) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/pause`, { method: 'POST' }
  ),
  resumeSellerListing: (listingId: string) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/resume`, { method: 'POST' }
  ),
  archiveSellerListing: (listingId: string) => request<SellerListing>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/archive`, { method: 'POST' }
  ),
  listingMedia: (listingId: string) => request<ListingMedia[]>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/media`
  ),
  listingMediaContent: (listingId: string, mediaId: string) => blobRequest(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/media/${encodeURIComponent(mediaId)}/content`
  ),
  publicListingImages: (listingId: string) => request<ListingMedia[]>(
    `/api/media/listings/${encodeURIComponent(listingId)}/images`
  ),
  uploadListingMedia: (listingId: string, mediaType: ListingMediaType, files: File[]) => {
    const body = new FormData();
    body.append('mediaType', mediaType);
    files.forEach(file => body.append('files', file));
    return uploadRequest<ListingMedia[]>(`/api/c2c/listings/${encodeURIComponent(listingId)}/media`, body);
  },
  deleteListingMedia: (listingId: string, mediaId: string) => request<void>(
    `/api/c2c/listings/${encodeURIComponent(listingId)}/media/${encodeURIComponent(mediaId)}`,
    { method: 'DELETE' }
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
  raffleStatus: (eventId: string, customerId?: string) => request<RaffleStatus>(
    `/api/marketplace/events/${encodeURIComponent(eventId)}/raffle/status${customerId ? `?customerId=${encodeURIComponent(customerId)}` : ''}`
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
  notificationInbox: () => request<NotificationInbox>('/api/c2c/notifications?page=0&size=50'),
  markNotificationRead: (notificationId: string) => request<MarketplaceNotification>(
    `/api/c2c/notifications/${encodeURIComponent(notificationId)}/read`, { method: 'PATCH' }
  ),
  markAllNotificationsRead: () => request<{ updatedCount: number }>(
    '/api/c2c/notifications/read-all', { method: 'PATCH' }
  ),
  addresses: () => request<ShippingAddress[]>('/api/c2c/addresses'),
  createAddress: (body: Omit<ShippingAddress, 'addressId'>) => request<ShippingAddress>('/api/c2c/addresses', {
    method: 'POST', body: JSON.stringify(body)
  }),
  setDefaultAddress: (addressId: string) => request<ShippingAddress>(`/api/c2c/addresses/${encodeURIComponent(addressId)}/default`, { method: 'PATCH' }),
  deleteAddress: (addressId: string) => request<void>(`/api/c2c/addresses/${encodeURIComponent(addressId)}`, { method: 'DELETE' }),
  confirmDelivery: (orderId: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/confirm-delivery`, { method: 'POST' }),
  cancelOrder: (orderId: string, reason: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/cancellations`, {
    method: 'POST', body: JSON.stringify({ reason })
  }),
  openDispute: (orderId: string, reason: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/dispute`, {
    method: 'POST', body: JSON.stringify({ reason })
  }),
  reviewSeller: (orderId: string, rating: number, comment: string) => request(`/api/c2c/orders/${encodeURIComponent(orderId)}/seller-review`, {
    method: 'POST', body: JSON.stringify({ rating, comment })
  }),
  listSellerReviews: (sellerId: string) =>
    request<SellerReview[]>(`/api/c2c/sellers/${encodeURIComponent(sellerId)}/reviews`),
  listWishlist: () => request<WishlistItem[]>('/api/c2c/wishlist'),
  addToWishlist: (saleEventId: string) =>
    request<WishlistItem>(`/api/c2c/wishlist/${encodeURIComponent(saleEventId)}`, { method: 'POST' }),
  removeFromWishlist: (saleEventId: string) =>
    request<void>(`/api/c2c/wishlist/${encodeURIComponent(saleEventId)}`, { method: 'DELETE' }),
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
    request<import('./types').CheckoutStatus>('/api/payments/toss/confirm', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  cancelToss: (intentId: string) => request<TossIntent>(
    `/api/payments/toss/intents/${encodeURIComponent(intentId)}/cancel`,
    { method: 'POST' }
  ),
  tossIntentStatus: (intentId: string) => request<import('./types').CheckoutStatus>(
    `/api/payments/toss/intents/${encodeURIComponent(intentId)}`
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

  // 결제창(payment)은 'API 개별 연동 키'의 클라이언트 키만 받는다.
  // 결제위젯 연동 키(test_gck_/live_gck_)를 넘기면 SDK가 내부 오류로 거절하는데,
  // 그 메시지만으로는 어느 키를 어디서 바꿔야 하는지 알기 어렵다.
  if (!intent.clientKey) {
    throw new ApiError('Toss 클라이언트 키가 서버에서 내려오지 않았습니다.', 503, 'TOSS_CLIENT_KEY_MISSING');
  }
  if (intent.clientKey.includes('_gck_')) {
    throw new ApiError(
      '결제위젯 연동 키는 지원하지 않습니다. API 개별 연동 키의 클라이언트 키(test_ck_/live_ck_)로 설정하세요.',
      503,
      'TOSS_WIDGET_KEY_NOT_SUPPORTED'
    );
  }

  const toss = window.TossPayments(intent.clientKey);
  // widgets()가 아니라 payment(). 결제창 방식이며 API 개별 연동 키를 쓴다.
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
