# Vendor JS (버전 고정, 폐쇄망용)

npm 레지스트리 패키지의 dist 파일을 그대로 복사한 것이다. CDN 은 쓰지 않는다. 업그레이드 시 이 표와 sha256 을 함께 갱신한다.

| 파일 | 출처 | sha256 |
|---|---|---|
| htmx.min.js | htmx.org@2.0.4 `dist/htmx.min.js` | e209dda5c8235479f3166defc7750e1dbcd5a5c1808b7792fc2e6733768fb447 |
| alpine.min.js | alpinejs@3.14.8 `dist/cdn.min.js` | b600e363d99d95444db54acbfb2deffec9ae792aa99a09229bcda078e5b55643 |
| alpine-focus.min.js | @alpinejs/focus@3.14.8 `dist/cdn.min.js` | 0326aee04b8abdaed23caba37025291a5b81a812daf938128bfe1e44845573ef |

alpine-focus.min.js 는 alpine.min.js 보다 먼저 로드하고, 둘 다 `defer` 로 로드한다.
