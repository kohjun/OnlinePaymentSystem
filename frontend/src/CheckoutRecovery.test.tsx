import { StrictMode } from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CheckoutRecovery } from './CheckoutRecovery';
import { api } from './api';

vi.mock('./api', () => ({ api: { confirmToss: vi.fn(), cancelToss: vi.fn(), tossIntentStatus: vi.fn() } }));
const storageKey = 'everysale.pendingCheckout:C-1';
const pending = { status: 'PENDING', terminal: false, phase: 'PAYMENT_RECONCILING', retryAfterSeconds: 5 };

describe('Checkout recovery', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers();
    sessionStorage.clear();
    window.history.replaceState({}, '', '/app/');
    vi.mocked(api.confirmToss).mockResolvedValue(pending);
    vi.mocked(api.tossIntentStatus).mockResolvedValue(pending);
  });
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it('confirms once under StrictMode and persists no provider key', async () => {
    window.history.replaceState({}, '', '/app/?tossResult=success&intentId=I-1&orderId=O-1&paymentKey=SECRET&amount=10000');
    await act(async () => { render(<StrictMode><CheckoutRecovery customerId="C-1" /></StrictMode>); });
    expect(api.confirmToss).toHaveBeenCalledTimes(1);
    expect(window.location.search).toBe('');
    expect(sessionStorage.getItem(storageKey)).toBe('I-1');
    expect(JSON.stringify(sessionStorage)).not.toContain('SECRET');
    expect(screen.getByText('결제 결과를 확인하고 있습니다')).toBeInTheDocument();
    expect(screen.queryByText('결제가 완료되었습니다')).not.toBeInTheDocument();
  });

  it('resumes a stored intent by GET and clears it only on terminal success', async () => {
    sessionStorage.setItem(storageKey, 'I-1');
    vi.mocked(api.tossIntentStatus).mockResolvedValueOnce(pending)
      .mockResolvedValueOnce({ status: 'SUCCESS', terminal: true });
    await act(async () => { render(<CheckoutRecovery customerId="C-1" />); });
    expect(sessionStorage.getItem(storageKey)).toBe('I-1');
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(screen.getByText('결제가 완료되었습니다')).toBeInTheDocument();
    expect(sessionStorage.getItem(storageKey)).toBeNull();
    expect(api.confirmToss).not.toHaveBeenCalled();
  });

  it('keeps the recovery reference when status lookup fails', async () => {
    sessionStorage.setItem(storageKey, 'I-1');
    vi.mocked(api.tossIntentStatus).mockRejectedValue(new Error('503'));
    await act(async () => { render(<CheckoutRecovery customerId="C-1" />); });
    expect(screen.getByText('거래 상태에 연결할 수 없습니다')).toBeInTheDocument();
    expect(sessionStorage.getItem(storageKey)).toBe('I-1');
    expect(screen.queryByText('결제가 완료되었습니다')).not.toBeInTheDocument();
  });
});
