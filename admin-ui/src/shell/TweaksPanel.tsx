import { useState } from 'react';
import { Sliders } from 'lucide-react';
import { useTweaks, type Tweaks } from '@/app/providers/TweaksProvider';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

const ACCENTS: Tweaks['accent'][] = ['indigo', 'violet', 'blue', 'teal', 'amber'];

// Visual swatch colors — preview only. Real CSS values are applied via
// :root[data-accent="..."] in tokens.css. Keep approximately in sync.
const ACCENT_SWATCH: Record<Tweaks['accent'], string> = {
  indigo: 'oklch(0.56 0.215 268)',
  violet: 'oklch(0.58 0.215 295)',
  blue:   'oklch(0.58 0.185 232)',
  teal:   'oklch(0.62 0.110 195)',
  amber:  'oklch(0.74 0.155 70)',
};

function Segment<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: readonly T[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex rounded border border-border bg-surface-2 p-0.5">
      {options.map((opt) => (
        <button
          key={opt}
          type="button"
          aria-pressed={value === opt}
          onClick={() => onChange(opt)}
          className={cn(
            'px-2.5 py-1 text-[12px] rounded-sm transition-colors',
            value === opt ? 'bg-surface text-text shadow-xs' : 'text-text-mute hover:text-text'
          )}
        >
          {opt}
        </button>
      ))}
    </div>
  );
}

export function TweaksPanel() {
  const { tweaks, setTweak, reset } = useTweaks();
  const [open, setOpen] = useState(false);

  return (
    <div className="fixed bottom-4 right-4 z-40">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" className="shadow-md" aria-label="Open visual tweaks panel">
            <Sliders className="h-3.5 w-3.5 mr-1.5" /> Tweaks
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" sideOffset={8} className="w-72">
          <div className="flex flex-col gap-3">
            <div>
              <Label>Theme</Label>
              <Segment
                value={tweaks.theme}
                options={['light', 'dark'] as const}
                onChange={(v) => setTweak('theme', v)}
              />
            </div>
            <div>
              <Label>Density</Label>
              <Segment
                value={tweaks.density}
                options={['compact', 'comfortable'] as const}
                onChange={(v) => setTweak('density', v)}
              />
            </div>
            <div>
              <Label>Accent</Label>
              <div className="flex gap-1.5">
                {ACCENTS.map((a) => (
                  <button
                    key={a}
                    type="button"
                    aria-label={`Accent ${a}`}
                    aria-pressed={tweaks.accent === a}
                    onClick={() => setTweak('accent', a)}
                    className={cn(
                      'h-6 w-6 rounded-full border-2 transition',
                      tweaks.accent === a ? 'border-text' : 'border-transparent hover:border-border-strong'
                    )}
                    style={{ background: ACCENT_SWATCH[a] }}
                  />
                ))}
              </div>
            </div>
            <div>
              <Label>Table style</Label>
              <Segment
                value={tweaks.tablestyle}
                options={['lines', 'striped', 'borderless'] as const}
                onChange={(v) => setTweak('tablestyle', v)}
              />
            </div>
            <div>
              <Label>Sidebar</Label>
              <Segment
                value={tweaks.sidebar}
                options={['labels', 'icons'] as const}
                onChange={(v) => setTweak('sidebar', v)}
              />
            </div>
            <div className="flex justify-end pt-1">
              <Button variant="ghost" size="sm" onClick={reset}>
                Reset
              </Button>
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
