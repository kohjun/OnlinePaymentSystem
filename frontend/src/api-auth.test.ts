import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, authToken, prepareOidcAuthorizationUrl } from './api';

describe('OIDC PKCE browser client', () => {
  const originalSubtle = globalThis.crypto.subtle;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    Object.defineProperty(globalThis.crypto, 'subtle', {
      configurable: true,
      value: { digest: vi.fn(async () => new Uint8Array(32).buffer) }
    });
  });

  afterEach(() => {
    Object.defineProperty(globalThis.crypto, 'subtle', { configurable: true, value: originalSubtle });
    vi.unstubAllGlobals();
  });

  it('creates an authorization request with state and an S256 challenge', async () => {
    const authorizationUrl = await prepareOidcAuthorizationUrl({
      mode: 'OIDC',
      authorizationUri: 'https://identity.example.com/authorize',
      tokenUri: 'https://identity.example.com/token',
      clientId: 'everysale-web',
      scopes: ['openid', 'profile', 'email']
    });

    const parsed = new URL(authorizationUrl);
    expect(parsed.searchParams.get('response_type')).toBe('code');
    expect(parsed.searchParams.get('client_id')).toBe('everysale-web');
    expect(parsed.searchParams.get('code_challenge_method')).toBe('S256');
    expect(parsed.searchParams.get('state')).toBe(sessionStorage.getItem('everysale.oidc.state'));
    expect(sessionStorage.getItem('everysale.oidc.verifier')?.length).toBeGreaterThan(40);
  });

  it('rejects an insecure non-local identity provider URL', async () => {
    await expect(prepareOidcAuthorizationUrl({
      mode: 'OIDC',
      authorizationUri: 'http://identity.example.com/authorize',
      tokenUri: 'https://identity.example.com/token',
      clientId: 'everysale-web',
      scopes: ['openid']
    })).rejects.toThrow('HTTPS');
  });

  it('keeps access tokens in session storage and removes legacy persistent copies', () => {
    localStorage.setItem('everysale.accessToken', 'legacy-token');

    expect(authToken.get()).toBe('legacy-token');
    expect(sessionStorage.getItem('everysale.accessToken')).toBe('legacy-token');
    expect(localStorage.getItem('everysale.accessToken')).toBeNull();
  });

  it('loads private seller media with the bearer token', async () => {
    authToken.set('seller-access-token');
    const fetchMock = vi.fn().mockResolvedValue(new Response('image-bytes', {
      status: 200,
      headers: { 'Content-Type': 'image/jpeg' }
    }));
    vi.stubGlobal('fetch', fetchMock);

    const blob = await api.listingMediaContent('LISTING/1', 'MEDIA/1');

    expect(blob.type).toBe('image/jpeg');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/c2c/listings/LISTING%2F1/media/MEDIA%2F1/content',
      expect.objectContaining({
        credentials: 'same-origin',
        headers: expect.objectContaining({ Authorization: 'Bearer seller-access-token' })
      })
    );
  });
});
