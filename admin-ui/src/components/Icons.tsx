// Icons.tsx — 30 inline SVG components (Phase 5 T2)
// SVG path data ported from docs/design-package/project/src/icons.jsx
// Stroke-based outline style; currentColor for theme adaptation.
// Substitutions: User (single-user, source only had Users/multi),
//                Spinner (not in source, standard animated ring),
//                BrandMark (P-letterform from design-package source)

export type IconProps = { size?: number; className?: string };

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true as const,
});

// ──────────────────────────────────────────────
// Brand
// ──────────────────────────────────────────────

/** Solid brand mark with "P" letterform and dot. Colour #4f46e5 per design-package. */
export function BrandMark({ size = 26, className }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      aria-hidden="true"
      className={className}
    >
      <rect x="1" y="1" width="30" height="30" rx="7" fill="#4f46e5" />
      <path d="M10 8h7a5 5 0 010 10h-3v6h-4V8zm4 4v2.5h2.5a1.25 1.25 0 000-2.5H14z" fill="white" />
      <circle cx="22" cy="22" r="2.6" fill="white" />
    </svg>
  );
}

// ──────────────────────────────────────────────
// Search / navigation
// ──────────────────────────────────────────────

export function Search({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.3-4.3" />
    </svg>
  );
}

export function ChevronDown({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M6 9l6 6 6-6" />
    </svg>
  );
}

export function ChevronRight({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M9 6l6 6-6 6" />
    </svg>
  );
}

export function Filter({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M3 4h18l-7 9v7l-4-2v-5L3 4z" />
    </svg>
  );
}

export function ExternalLink({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M14 4h6v6" />
      <path d="M20 4L11 13" />
      <path d="M19 14v5a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h5" />
    </svg>
  );
}

// ──────────────────────────────────────────────
// Actions
// ──────────────────────────────────────────────

export function Plus({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

export function Check({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M5 12l5 5L20 7" />
    </svg>
  );
}

export function X({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

export function Copy({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5a2 2 0 012-2h10" />
    </svg>
  );
}

export function Download({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M12 3v12m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2" />
    </svg>
  );
}

export function Trash({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2" />
      <path d="M5 6l1 14a2 2 0 002 2h8a2 2 0 002-2l1-14" />
    </svg>
  );
}

export function Refresh({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M21 12a9 9 0 11-3-6.7L21 8M21 3v5h-5" />
    </svg>
  );
}

// ──────────────────────────────────────────────
// Status / alerts
// ──────────────────────────────────────────────

export function Alert({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M12 3l10 18H2L12 3z" />
      <path d="M12 10v4M12 18h.01" />
    </svg>
  );
}

export function Info({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v6M12 8h.01" />
    </svg>
  );
}

export function Activity({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M3 12h4l3-8 4 16 3-8h4" />
    </svg>
  );
}

/** Animated loading spinner — not in source, standard outline ring.
 *  Apply the `icon-spin` class (defined in tokens.css) for animation. */
export function Spinner({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={['icon-spin', className].filter(Boolean).join(' ')}>
      <circle cx="12" cy="12" r="9" strokeOpacity={0.25} />
      <path d="M12 3a9 9 0 019 9" />
    </svg>
  );
}

// ──────────────────────────────────────────────
// Entities / domain
// ──────────────────────────────────────────────

export function Building({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M4 21V5a2 2 0 012-2h8a2 2 0 012 2v16" />
      <path d="M16 8h2a2 2 0 012 2v11" />
      <path d="M8 7h2M8 11h2M8 15h2M14 11h2M14 15h2" />
    </svg>
  );
}

export function Key({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="8" cy="15" r="3" />
      <path d="M10.5 13l9-9M16 8l3 3" />
    </svg>
  );
}

export function Receipt({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M4 4h16v18l-3-2-3 2-3-2-3 2-3-2-1 2V4z" />
      <path d="M8 9h8M8 13h8M8 17h5" />
    </svg>
  );
}

export function Shield({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M12 3l8 3v6c0 5-3.5 8.5-8 9-4.5-.5-8-4-8-9V6l8-3z" />
    </svg>
  );
}

export function Cog({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 01-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 01-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 012.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 014 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 012.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 010 4h-.1a1.7 1.7 0 00-1.5 1z" />
    </svg>
  );
}

/** Single user — source only had Users (multi). Standard single-person outline. */
export function User({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" />
    </svg>
  );
}

export function Logout({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M9 3H5a2 2 0 00-2 2v14a2 2 0 002 2h4" />
      <path d="M16 17l5-5-5-5M21 12H9" />
    </svg>
  );
}

export function Hash({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M4 9h16M4 15h16M10 3L8 21M16 3l-2 18" />
    </svg>
  );
}

export function Fingerprint({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M6 11a6 6 0 0112 0M5 16c2-2 3-4 3-6M19 11c0 4-2 7-4 9M12 11v3c0 2-1 4-2 6M9 21c2-2 3-5 3-9" />
    </svg>
  );
}

export function Globe({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18" />
    </svg>
  );
}

// ──────────────────────────────────────────────
// Auth / security
// ──────────────────────────────────────────────

export function Lock({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 018 0v4" />
    </svg>
  );
}

export function Eye({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function EyeOff({ size = 16, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M3 3l18 18" />
      <path d="M10.6 6.1A10 10 0 0112 6c6.5 0 10 6 10 6a17.3 17.3 0 01-3.7 4.6" />
      <path d="M6.2 6.2A17.3 17.3 0 002 12s3.5 6 10 6a10 10 0 003.5-.6" />
    </svg>
  );
}

// Alias: source and design mocks reference "LogOut" (camelCase).
export { Logout as LogOut };
