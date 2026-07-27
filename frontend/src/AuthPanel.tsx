import { useState } from 'react';
import { ChevronLeft, Loader2, LogIn, UserPlus } from 'lucide-react';
import { api, authToken } from './api';

/**
 * 로그인 / 회원가입.
 *
 * 로컬에서는 서버가 목 인증으로 기본 계정을 붙여주므로 로그인 없이도
 * 화면이 열린다. 여기서 로그인하면 발급받은 토큰이 저장되고, 이후 요청은
 * 그 계정으로 처리된다. 운영에서는 목 인증이 꺼져 있어 로그인이 필수다.
 */
export function AuthPanel({ close, notify, onAuthenticated }: {
  close: () => void;
  notify: (message: string, tone?: 'success' | 'error' | 'info') => void;
  onAuthenticated: () => void;
}) {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);

  const signingUp = mode === 'signup';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const result = signingUp
        ? await api.signUp(email, password, displayName)
        : await api.login(email, password);

      authToken.set(result.accessToken);
      notify(`${result.displayName || result.email}님으로 로그인했습니다.`, 'success');
      onAuthenticated();
      close();
    } catch (error) {
      notify(error instanceof Error ? error.message : '요청을 처리하지 못했습니다.', 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="auth-view" aria-labelledby="auth-title">
      <header>
        <button className="icon-button" type="button" onClick={close} title="마켓으로"><ChevronLeft /></button>
        <div>
          <h1 id="auth-title">{signingUp ? '회원가입' : '로그인'}</h1>
          <p>{signingUp ? '이메일과 비밀번호로 계정을 만듭니다.' : '가입한 계정으로 로그인합니다.'}</p>
        </div>
      </header>

      <div className="auth-tabs" role="tablist" aria-label="인증 방식">
        <button role="tab" type="button" aria-selected={!signingUp} onClick={() => setMode('login')}>로그인</button>
        <button role="tab" type="button" aria-selected={signingUp} onClick={() => setMode('signup')}>회원가입</button>
      </div>

      <form className="auth-form" onSubmit={submit}>
        <label>
          이메일
          <input type="email" required autoComplete="email" value={email}
                 onChange={event => setEmail(event.target.value)} placeholder="buyer@everysale.dev" />
        </label>
        <label>
          비밀번호
          <input type="password" required minLength={8}
                 autoComplete={signingUp ? 'new-password' : 'current-password'}
                 value={password} onChange={event => setPassword(event.target.value)}
                 placeholder="8자 이상" />
        </label>
        {signingUp && (
          <label>
            표시 이름 <span className="auth-form__optional">(선택)</span>
            <input value={displayName} maxLength={50}
                   onChange={event => setDisplayName(event.target.value)} placeholder="마켓에 보일 이름" />
          </label>
        )}

        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? <Loader2 className="spin" /> : signingUp ? <UserPlus /> : <LogIn />}
          {signingUp ? '가입하고 시작하기' : '로그인'}
        </button>
      </form>

      <p className="auth-hint">
        로그인하지 않아도 둘러볼 수 있습니다. 로컬 개발 환경에서는 기본 계정으로 동작합니다.
      </p>
    </section>
  );
}
