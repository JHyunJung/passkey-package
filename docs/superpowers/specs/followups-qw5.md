## perf-sidebar-not-memoized-inline-props (보류 — QW-5 선택)
NavBtn React.memo 는 사용처(Sidebar.tsx:170/172)가 map 내부 인라인 onClick
클로저를 매 렌더 생성하므로 memo 효과 0. onClick 안정화(useCallback)는 항목별
클로저라 안정화가 어렵고 로직 흐름/외관 변경 리스크 > 이득. minimal-impact·
외관 불변 원칙에 따라 변경하지 않음. nav 항목 수가 폭증해 측정 가능한 병목이
되면 NavBtn 추출 + 항목 id 기반 단일 onSelect(id) 핸들러로 재설계 검토.
