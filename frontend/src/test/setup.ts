import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Testing Library는 vitest의 globals가 켜져 있을 때만 자동으로 cleanup을
// 등록한다. 이 프로젝트는 globals를 쓰지 않으므로 직접 걸어야 한다.
// 없으면 이전 테스트가 렌더한 DOM이 남아, 같은 이름의 요소를 찾을 때
// 죽은 컴포넌트의 노드를 집어 조용히 엉뚱한 결과가 나온다.
afterEach(() => {
  cleanup();
});
