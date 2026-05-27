interface Props {
    page: number;
    size: number;
    total: number;
    onChange: (page: number) => void;
}

export default function Pagination({ page, size, total, onChange }: Props) {
    const totalPages = Math.max(1, Math.ceil(total / size));
    const canPrev = page > 0;
    const canNext = page < totalPages - 1;

    return (
        <div className="row" style={{ gap: 8, justifyContent: 'flex-end', alignItems: 'center', fontSize: 13 }}>
            <span className="muted">
                {total === 0
                    ? '0 / 0'
                    : `${page * size + 1}–${Math.min((page + 1) * size, total)} / ${total}`}
            </span>
            <button className="btn btn--sm" disabled={!canPrev} onClick={() => onChange(0)}>{'«'}</button>
            <button className="btn btn--sm" disabled={!canPrev} onClick={() => onChange(page - 1)}>{'‹'}</button>
            <span style={{ minWidth: 56, textAlign: 'center' }}>{page + 1} / {totalPages}</span>
            <button className="btn btn--sm" disabled={!canNext} onClick={() => onChange(page + 1)}>{'›'}</button>
            <button className="btn btn--sm" disabled={!canNext} onClick={() => onChange(totalPages - 1)}>{'»'}</button>
        </div>
    );
}
