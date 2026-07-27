-- 자체 계정 로그인 자격증명.
--
-- 기존 users 테이블은 외부 IdP가 인증한 사용자를 기록만 하는 용도였다.
-- 여기에 비밀번호 해시를 더해 자체 회원가입/로그인을 지원한다.
-- 외부 인증으로 들어온 계정은 password_hash가 NULL로 남고, 그 계정으로는
-- 자체 로그인이 되지 않는다.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

-- 로그인 식별자. 이메일은 애플리케이션에서 소문자로 정규화해 저장하므로
-- 표현식 인덱스 없이 평범한 유니크 인덱스로 충분하다. 외부 인증 계정은
-- email이 NULL일 수 있고, NULL은 유니크 제약에서 서로 충돌하지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email
    ON users (email);
