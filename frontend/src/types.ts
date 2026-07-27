export type SaleType = 'FIXED_PRICE' | 'DROP' | 'RAFFLE' | 'AUCTION';
export type EventStatus = 'SCHEDULED' | 'LIVE' | 'ENDED' | 'CANCELLED';
export type ListingStatus = 'DRAFT' | 'PENDING_REVIEW' | 'ACTIVE' | 'REJECTED' | 'PAUSED' | 'ENDED';
export type SellerVerificationStatus = 'UNVERIFIED' | 'PENDING_REVIEW' | 'VERIFIED' | 'REJECTED';
export type FulfillmentStatus = 'NOT_READY' | 'READY_TO_FULFILL' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';
export type SellerPayoutStatus = 'HELD' | 'READY_FOR_RELEASE' | 'DISPUTED' | 'RELEASED' | 'CANCELLED' | 'RECOVERY_REQUIRED' | 'RECOVERED';

export interface SellerProfile {
  sellerId: string;
  displayName: string;
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED';
  verificationStatus: SellerVerificationStatus;
  verificationEvidenceRef?: string;
  verificationNote?: string;
  verificationSubmittedAt?: string;
  verificationReviewedAt?: string;
}

export interface MarketplaceEvent {
  saleEventId: string;
  listingId: string;
  sellerId: string;
  sellerName: string;
  sellerVerificationStatus: string;
  productId: string;
  title: string;
  description: string;
  imageUrl?: string;
  category?: string;
  digitalTicket: boolean;
  saleType: SaleType;
  status: EventStatus;
  price: number;
  currency: string;
  totalQuantity: number;
  availableQuantity: number;
  minBidIncrement?: number;
  reservePrice?: number;
  startsAt?: string;
  endsAt?: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface Identity {
  userId: string;
  customerId: string;
  sellerId?: string;
  roles: string[];
  user?: { displayName?: string; email?: string };
  buyerProfile?: { displayName?: string };
  sellerProfile?: SellerProfile;
}

export interface SellerListing {
  listingId: string;
  sellerId: string;
  productId: string;
  name: string;
  description?: string;
  imageUrl?: string;
  category: string;
  itemCondition: string;
  brand?: string;
  tags?: string;
  authenticityNote?: string;
  defectDescription?: string;
  status: ListingStatus;
  price: number;
  totalQuantity: number;
  availableQuantity: number;
  saleEventId?: string;
  saleType?: SaleType;
  saleEventStatus?: EventStatus;
  startsAt?: string;
  endsAt?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewNote?: string;
}

export interface SellerListingInput {
  name: string;
  description?: string;
  price: number;
  category: string;
  quantity: number;
  imageUrl?: string;
  itemCondition: string;
  brand?: string;
  tags?: string;
  authenticityNote?: string;
  defectDescription?: string;
}

export interface SaleEventInput {
  saleType: SaleType;
  price: number;
  stockQuantity: number;
  startsAt?: string;
  endsAt?: string;
  minBidIncrement?: number;
  reservePrice?: number;
  publishImmediately: boolean;
}

export interface AuctionBid {
  bidId: string;
  customerId: string;
  bidAmount: number;
  status: string;
  createdAt: string;
}

export interface AuctionStatus {
  saleEventId: string;
  eventStatus: EventStatus;
  highestBid: number;
  highestBidder?: string;
  minNextBid: number;
  closed: boolean;
  winnerCustomerId?: string;
  currentUserWinning?: boolean;
  secondsRemaining?: number;
  checkoutExpiresAt?: string;
  endsAt?: string;
  history: AuctionBid[];
}

export interface RaffleStatus {
  saleEventId: string;
  eventStatus: EventStatus;
  entryCount: number;
  winnerCount: number;
  completedCheckoutCount: number;
  entered?: boolean;
  winner?: boolean;
  drawn: boolean;
  entryStatus?: string;
  checkoutStatus?: string;
  winnerAliases: string[];
  drawSeedCommitment?: string;
  entrySnapshotHash?: string;
  endsAt?: string;
  checkoutExpiresAt?: string;
}

export interface TossIntent {
  intentId: string;
  orderId: string;
  orderName: string;
  amount: number;
  currency: string;
  customerKey: string;
  clientKey: string;
  successUrl: string;
  failUrl: string;
}

export interface MarketplaceOrder {
  marketplaceOrderId: string;
  sellerId: string;
  customerId?: string;
  saleEventId?: string;
  listingId?: string;
  productId?: string;
  quantity?: number;
  title?: string;
  amount: number;
  currency: string;
  status: string;
  fulfillmentStatus: string;
  seatId?: string;
  trackingCarrier?: string;
  trackingNumber?: string;
  buyerConfirmedAt?: string;
  disputedAt?: string;
  disputeReason?: string;
  disputeResolution?: string;
  shippingRecipientName?: string;
  shippingContactPhone?: string;
  shippingPostalCode?: string;
  shippingAddress?: string;
  shippingMemo?: string;
  shippedAt?: string;
  fulfilledAt?: string;
  createdAt: string;
}

export interface SellerPayout {
  payoutId: string;
  sellerId: string;
  sourceType: string;
  sourceId: string;
  grossAmount: number;
  platformFee: number;
  netAmount: number;
  status: SellerPayoutStatus;
  createdAt: string;
  releasedAt?: string;
}

export interface SellerPayoutAccount {
  payoutAccountId: string;
  sellerId: string;
  accountRef: string;
  bankCode: string;
  bankName: string;
  accountHolderName: string;
  accountLast4: string;
  status: 'PENDING_REVIEW' | 'VERIFIED' | 'REJECTED';
  reviewNote?: string;
  submittedAt?: string;
  reviewedAt?: string;
}

export interface SellerPayoutAccountInput {
  accountRef: string;
  bankCode: string;
  bankName: string;
  accountHolderName: string;
  accountLast4: string;
  note?: string;
}

export interface AdminQueueAction {
  action: string;
  label: string;
  tone: 'success' | 'danger' | 'neutral';
  noteRequired: boolean;
}

export interface AdminQueueItem {
  id: string;
  type: string;
  status: string;
  title: string;
  ownerId?: string;
  amount?: number;
  createdAt?: string;
  metadata: Record<string, unknown>;
  actions: AdminQueueAction[];
}

export interface AdminQueueSummary {
  queue: string;
  label: string;
  count: number;
  items: AdminQueueItem[];
}

export interface AdminOperationsQueues {
  generatedAt: string;
  totalOpen: number;
  queues: AdminQueueSummary[];
}

export interface AdminAuditEvent {
  eventId: string;
  actorId: string;
  actorRoles?: string;
  action: string;
  queue: string;
  resourceId: string;
  outcome: string;
  reason?: string;
  ipAddress?: string;
  createdAt: string;
}

export interface AdminOperationsAudit {
  generatedAt: string;
  events: AdminAuditEvent[];
}

export interface SystemHealth {
  status: string;
  components?: Record<string, { status?: string; connected?: boolean }>;
  timestamp: number;
  error?: string;
}

export interface ReadinessCheck {
  id: string;
  name: string;
  status: 'PASS' | 'WARN' | 'FAIL';
  blocking: boolean;
  message: string;
}

export interface DistributionReadiness {
  status: string;
  releasable: boolean;
  mode: string;
  brandName: string;
  releaseChannel: string;
  checks: ReadinessCheck[];
  blockingIssues: string[];
  warnings: string[];
  timestamp: number;
}

export interface InventoryMismatch {
  productId: string;
  postgresAvailable?: number;
  redisAvailable?: number;
  postgresReserved?: number;
  redisReserved?: number;
  reason: string;
}

export interface InventoryInspection {
  status: 'CONSISTENT' | 'MISMATCH_DETECTED' | 'UNAVAILABLE';
  mismatchCount: number;
  mismatches: InventoryMismatch[];
  message: string;
}

export interface QueueStatus {
  status: 'ACTIVE' | 'WAITING' | 'NONE';
  rank: number;
  waitingTime: number;
  queueEnabled: boolean;
  message: string;
}

export interface TicketSeat {
  seatId: string;
  section: string;
  rowLabel: string;
  seatNumber: number;
  label: string;
  status: 'AVAILABLE' | 'HELD' | 'SOLD';
  ownedByCurrentUser: boolean;
  holdExpiresAt?: string;
}

export interface TicketSeatMap {
  saleEventId: string;
  eventStatus: EventStatus;
  totalCount: number;
  availableCount: number;
  heldCount: number;
  soldCount: number;
  holdSeconds: number;
  seats: TicketSeat[];
}

export interface TicketSeatHold {
  saleEventId: string;
  seatId: string;
  status: 'HELD';
  expiresAt: string;
}

export interface ShippingAddress {
  addressId: string;
  label?: string;
  recipientName: string;
  contactPhone: string;
  postalCode?: string;
  addressLine1: string;
  addressLine2?: string;
  deliveryMemo?: string;
  defaultAddress: boolean;
}

export interface SellerReview {
  reviewId: string;
  marketplaceOrderId: string;
  reviewerCustomerId: string;
  targetSellerId: string;
  rating: number;
  comment?: string;
  createdAt?: string;
}

export interface WishlistItem {
  wishlistItemId: string;
  saleEventId: string;
  createdAt?: string;
  /** 판매가 끝났거나 비공개로 바뀐 이벤트는 비어 있을 수 있다. */
  event?: MarketplaceEvent;
}
