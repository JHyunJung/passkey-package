## perf-sidebar-not-memoized-inline-props (보류 — QW-5 선택)
NavBtn React.memo 는 사용처(Sidebar.tsx:170/172)가 map 내부 인라인 onClick
클로저를 매 렌더 생성하므로 memo 효과 0. onClick 안정화(useCallback)는 항목별
클로저라 안정화가 어렵고 로직 흐름/외관 변경 리스크 > 이득. minimal-impact·
외관 불변 원칙에 따라 변경하지 않음. nav 항목 수가 폭증해 측정 가능한 병목이
되면 NavBtn 추출 + 항목 id 기반 단일 onSelect(id) 핸들러로 재설계 검토.

## perf-audittab-payload-stringify-per-row (보류 — QW-5 선택)
AuditTab.tsx 의 행당 JSON.stringify(e.payload) 는 filtered(이미 useMemo)
입력 변경 시에만 재실행되고, 메모하려면 filtered 항목 형태에 payloadStr 를
추가해야 해 CSV export(L460)·PayloadDialog(L337) 등 다른 소비처와 타입 표면이
얽힌다. 표시 문자열 바이트 보존 + 회귀 위험 대비 이득이 작아 미변경. payload 가
큰 테넌트에서 측정 병목이 확인되면 행 컴포넌트 추출 + React.memo + 사전 직렬화
필드로 재설계 검토.
