import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthPanel } from './AuthPanel';
import { api } from './api';

vi.mock('./api', () => ({
  authToken: { set: vi.fn() },
  prepareOidcAuthorizationUrl: vi.fn(),
  api: {
    authConfig: vi.fn(),
    signUp: vi.fn(),
    login: vi.fn()
  }
}));

describe('AuthPanel runtime mode', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows local email authentication in development mode', async () => {
    vi.mocked(api.authConfig).mockResolvedValue({ mode: 'LOCAL', scopes: [] });

    render(<AuthPanel close={vi.fn()} notify={vi.fn()} onAuthenticated={vi.fn()} />);

    expect(await screen.findByLabelText('이메일')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '회원가입' })).toBeInTheDocument();
  });

  it('shows the OIDC PKCE provider instead of collecting a password', async () => {
    vi.mocked(api.authConfig).mockResolvedValue({
      mode: 'OIDC',
      authorizationUri: 'https://identity.example.com/authorize',
      tokenUri: 'https://identity.example.com/token',
      clientId: 'everysale-web',
      scopes: ['openid', 'profile', 'email']
    });

    render(<AuthPanel close={vi.fn()} notify={vi.fn()} onAuthenticated={vi.fn()} />);

    expect(await screen.findByText('Authorization Code + PKCE')).toBeInTheDocument();
    expect(screen.queryByLabelText('비밀번호')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /계속하기/ })).toBeEnabled();
  });
});
