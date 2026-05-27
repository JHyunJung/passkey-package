import type { Config } from 'tailwindcss';

const config: Config = {
  darkMode: ['selector', '[data-theme="dark"]'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--bg)',
        surface: 'var(--surface)',
        'surface-2': 'var(--surface-2)',
        'surface-3': 'var(--surface-3)',
        'surface-sunk': 'var(--surface-sunk)',
        border: 'var(--border)',
        'border-subtle': 'var(--border-subtle)',
        'border-strong': 'var(--border-strong)',
        text: 'var(--text)',
        'text-soft': 'var(--text-soft)',
        'text-mute': 'var(--text-mute)',
        'text-faint': 'var(--text-faint)',
        accent: {
          DEFAULT: 'var(--accent)',
          hover: 'var(--accent-hover)',
          press: 'var(--accent-press)',
          soft: 'var(--accent-soft)',
          'soft-2': 'var(--accent-soft-2)',
          fg: 'var(--accent-fg)',
        },
        success: { DEFAULT: 'var(--success)', soft: 'var(--success-soft)' },
        warning: { DEFAULT: 'var(--warning)', soft: 'var(--warning-soft)' },
        danger:  { DEFAULT: 'var(--danger)',  soft: 'var(--danger-soft)' },
        info:    { DEFAULT: 'var(--info)',    soft: 'var(--info-soft)' },
        violet:  { DEFAULT: 'var(--violet)',  soft: 'var(--violet-soft)' },
        teal:    { DEFAULT: 'var(--teal)',    soft: 'var(--teal-soft)' },
      },
      fontFamily: { sans: 'var(--font)', mono: 'var(--mono)' },
      borderRadius: {
        xs: 'var(--radius-xs)', sm: 'var(--radius-sm)', DEFAULT: 'var(--radius)',
        lg: 'var(--radius-lg)', xl: 'var(--radius-xl)', pill: 'var(--radius-pill)',
      },
      boxShadow: {
        xs: 'var(--shadow-xs)', sm: 'var(--shadow-sm)', md: 'var(--shadow-md)',
        lg: 'var(--shadow-lg)', focus: 'var(--focus-ring)',
      },
      transitionTimingFunction: { out: 'var(--ease-out)', 'in-out': 'var(--ease-in-out)', spring: 'var(--ease-spring)' },
      transitionDuration: { fast: 'var(--dur-fast)', DEFAULT: 'var(--dur)', slow: 'var(--dur-slow)' },
    },
  },
  plugins: [],
};

export default config;
