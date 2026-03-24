import { useState, useMemo } from 'react';
import { AnsiUp } from 'ansi_up';
import { RichBlock } from './RichBlock';
import {
  Collapsible,
  CollapsibleTrigger,
  CollapsibleContent,
} from '@/components/ui/collapsible';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

// ── Singleton AnsiUp instance ──

const ansi = new AnsiUp();
ansi.use_classes = false;

// ── Types ──

interface CollapsibleOutputProps {
  outputSource: string;
}

interface OutputSection {
  title: string;
  content: string;
  lineCount: number;
}

// ── Section parser ──

function parseSections(text: string): OutputSection[] {
  const lines = text.split('\n');
  const sections: OutputSection[] = [];
  let currentTitle = '';
  let currentLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith('### ')) {
      if (currentLines.length > 0 || currentTitle) {
        sections.push({
          title: currentTitle || 'Output',
          content: currentLines.join('\n'),
          lineCount: currentLines.filter((l) => l.trim().length > 0).length,
        });
      }
      currentTitle = line.slice(4).trim();
      currentLines = [];
    } else {
      currentLines.push(line);
    }
  }

  // Push last section
  if (currentLines.length > 0 || currentTitle) {
    sections.push({
      title: currentTitle || 'Output',
      content: currentLines.join('\n'),
      lineCount: currentLines.filter((l) => l.trim().length > 0).length,
    });
  }

  return sections;
}

// ── Chevron icon ──

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 12 12"
      fill="none"
      className={cn(
        'shrink-0 transition-transform duration-200',
        open && 'rotate-180',
      )}
      style={{ color: 'var(--fg-muted)' }}
    >
      <path
        d="M3 4.5L6 7.5L9 4.5"
        stroke="currentColor"
        strokeWidth="1.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

// ── Section block ──

function SectionBlock({
  section,
  defaultOpen,
}: {
  section: OutputSection;
  defaultOpen: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const html = useMemo(() => ansi.ansi_to_html(section.content), [section.content]);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <button
          className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] font-medium transition-colors hover:bg-[var(--hover-overlay)]"
          style={{ color: 'var(--fg)', borderBottom: '1px solid var(--border)' }}
        >
          <ChevronIcon open={open} />
          <span className="flex-1">{section.title}</span>
          <Badge
            variant="secondary"
            className="text-[9px] px-1.5 py-0"
          >
            {section.lineCount} lines
          </Badge>
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <pre
          className="px-3 py-2 text-[11px] leading-relaxed overflow-x-auto"
          style={{
            color: 'var(--fg)',
            maxHeight: '300px',
            overflowY: 'auto',
            fontFamily: "var(--font-mono, 'JetBrains Mono', monospace)",
          }}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </CollapsibleContent>
    </Collapsible>
  );
}

// ── Main component ──

export function CollapsibleOutput({ outputSource }: CollapsibleOutputProps) {
  const sections = useMemo(() => parseSections(outputSource), [outputSource]);

  // No sections (no ### headers) — render as single scrollable block
  if (sections.length <= 1 && sections[0]?.title === 'Output') {
    const html = ansi.ansi_to_html(outputSource);
    return (
      <RichBlock type="output" source={outputSource}>
        <pre
          className="px-3 py-2 text-[11px] leading-relaxed overflow-x-auto"
          style={{
            color: 'var(--fg)',
            maxHeight: '400px',
            overflowY: 'auto',
            fontFamily: "var(--font-mono, 'JetBrains Mono', monospace)",
          }}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </RichBlock>
    );
  }

  return (
    <RichBlock type="output" source={outputSource}>
      <div>
        {sections.map((section, i) => (
          <SectionBlock key={i} section={section} defaultOpen={i === 0} />
        ))}
      </div>
    </RichBlock>
  );
}
