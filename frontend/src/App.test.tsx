import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

vi.mock('./api', () => ({
  ApiError: class extends Error {
    constructor(message: string, readonly status: number) { super(message); }
  },
  completeOidcCallbackIfPresent: vi.fn(() => Promise.resolve(false)),
  authToken: { get: vi.fn(() => undefined), set: vi.fn(), clear: vi.fn() },
  api: {
    me: vi.fn(),
    events: vi.fn(),
    listWishlist: vi.fn(),
    notificationInbox: vi.fn(),
    authConfig: vi.fn(() => Promise.resolve({ mode: 'LOCAL', scopes: [] }))
  }
}));

import { ApiError, api } from './api';

describe('App guest storefront', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.me).mockRejectedValue(new ApiError('Authentication required', 401));
    vi.mocked(api.events).mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 24, first: true, last: true
    } as never);
  });

  it('renders the public marketplace after an unauthenticated identity response', async () => {
    render(<App />);

    expect(await screen.findByRole('heading', { name: '마켓플레이스' })).toBeInTheDocument();
    expect(screen.getByText('방문자')).toBeInTheDocument();
  });

  it('opens login when a guest tries to enter seller tools', async () => {
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: /상품 판매/ }));

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(screen.getByText('계속하려면 로그인해 주세요.')).toBeInTheDocument();
  });
});
