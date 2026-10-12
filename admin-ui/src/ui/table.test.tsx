import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { Tabs, DataTable, Pagination, type Column } from '@/ui';

type R = { id: string; day: string; name: string };
const cols: Column<R>[] = [{ key: 'name', header: '이름', render: (r) => r.name }];
const rows: R[] = [{ id: '1', day: '오늘', name: 'a' }, { id: '2', day: '오늘', name: 'b' }, { id: '3', day: '어제', name: 'c' }];

describe('Tabs', () => {
  it('aria-selected 와 badge/count', () => {
    const onChange = vi.fn();
    render(<Tabs ariaLabel="Tenant 탭" active="overview" onChange={onChange} items={[{ id: 'overview', label: '개요' }, { id: 'aaguid', label: 'AAGUID 정책', badge: { text: '확인 필요 1', reason: 'MDS 미확인 AAGUID 1건' } }, { id: 'admins', label: 'Admin 사용자', count: 3 }]} />);
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('확인 필요 1')).toHaveAttribute('title', 'MDS 미확인 AAGUID 1건');
    expect(screen.getByText('3')).toHaveClass('tabs__count');
    fireEvent.click(screen.getByRole('tab', { name: /AAGUID/ }));
    expect(onChange).toHaveBeenCalledWith('aaguid');
  });
});

describe('DataTable', () => {
  it('그룹 헤더 행을 라벨이 바뀔 때마다 넣는다', () => {
    const { container } = render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} groupBy={(r) => r.day} />);
    const groups = container.querySelectorAll('tr.table__group');
    expect(Array.from(groups).map((g) => g.textContent)).toEqual(['오늘', '어제']);
  });
  it('행 클릭과 키보드 Enter', () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    fireEvent.click(screen.getByText('a'));
    fireEvent.keyDown(screen.getByText('b').closest('tr')!, { key: 'Enter' });
    expect(onRowClick.mock.calls.map((c) => c[0].id)).toEqual(['1', '2']);
  });
  it('행 안 버튼에서 누른 Enter 는 행 클릭으로 처리하지 않는다', () => {
    const onRowClick = vi.fn();
    const withBtn: Column<R>[] = [...cols, { key: 'act', header: '액션', render: (r) => <button type="button">act-{r.name}</button> }];
    render(<DataTable columns={withBtn} rows={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    fireEvent.keyDown(screen.getByRole('button', { name: 'act-a' }), { key: 'Enter' });
    expect(onRowClick).not.toHaveBeenCalled();
  });
  it('expandedKey 행 아래 펼침 행', () => {
    const { container } = render(<DataTable columns={cols} rows={rows} rowKey={(r) => r.id} expandedKey="2" renderExpanded={(r) => <div>detail-{r.name}</div>} />);
    expect(screen.getByText('detail-b')).toBeInTheDocument();
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(1);
  });
  it('빈 rows 는 empty 슬롯', () => {
    render(<DataTable columns={cols} rows={[]} rowKey={(r) => r.id} empty={<p>없음</p>} />);
    expect(screen.getByText('없음')).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  it('범위 텍스트와 경계 비활성', () => {
    const onPage = vi.fn();
    render(<Pagination page={1} pageSize={50} total={120} onPage={onPage} />);
    expect(screen.getByText('1–50 / 120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(onPage).toHaveBeenCalledWith(2);
  });
  it('마지막 페이지와 0건', () => {
    const { rerender } = render(<Pagination page={3} pageSize={50} total={120} onPage={() => {}} />);
    expect(screen.getByText('101–120 / 120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    rerender(<Pagination page={1} pageSize={50} total={0} onPage={() => {}} />);
    expect(screen.getByText('0건')).toBeInTheDocument();
  });
  it('page 가 마지막을 넘으면 마지막 페이지로 보정', () => {
    const onPage = vi.fn();
    render(<Pagination page={5} pageSize={50} total={120} onPage={onPage} />);
    expect(screen.getByText('101–120 / 120건')).toBeInTheDocument();
    expect(screen.getByText('3 / 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '이전 페이지' }));
    expect(onPage).toHaveBeenCalledWith(2);
  });
  it('페이지당 선택', () => {
    const onPageSize = vi.fn();
    render(<Pagination page={1} pageSize={50} total={10} onPage={() => {}} pageSizeOptions={[50, 100, 200]} onPageSize={onPageSize} />);
    fireEvent.change(within(screen.getByText(/페이지당/).closest('label')!).getByRole('combobox'), { target: { value: '100' } });
    expect(onPageSize).toHaveBeenCalledWith(100);
  });
});
