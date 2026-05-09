import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mermaid from 'mermaid';

// ── Wire-format types — mirror Kotlin data classes in agent/tools/docs/ ──────

export interface ToolDocPayload {
  toolName: string;
  tier: string;
  sideEffect: SideEffectKind;
  counterfactual: string | null;
  commonLLMMistakes: string[];
  metadata: AutoDerivedMetadata;
  summary: ToolSummary;
  whatLLMSees: string;
  actions: ActionDoc[] | null;
  singleActionParams: ParamGroup | null;
  toolVerdict: Verdict;
  auditNotes: AuditNote[];
  relatedTools: RelatedTool[];
  flowchart: string | null;
  downsides: string[];
  narrative: string | null;
}

type SideEffectKind =
  | 'READ_ONLY'
  | 'AGENT_CONTROL'
  | 'FILE_WRITE'
  | 'PROCESS_SPAWN'
  | 'NETWORK'
  | 'IDE_MUTATION';

interface AutoDerivedMetadata {
  tier: string;
  registrationCondition: string;
  schemaTokenCost: number;
  approvalPolicy: string;
  planModeBlocked: boolean;
  allowedWorkers: string[];
  timeoutClass: string;
  outputCap: string;
  isWriteTool: boolean;
}

interface ToolSummary {
  technical: string;
  plain: string;
}

interface ActionDoc {
  name: string;
  description: ToolSummary;
  whenLLMUses: string;
  requiredParams: ParamDoc[];
  optionalParams: ParamDoc[];
  rejectedParams: RejectedParam[];
  preconditions: string[];
  onSuccess: string;
  onFailure: FailureMode[];
  examples: ToolExample[];
  flowchart: string | null;
  verdict: Verdict;
}

interface ParamGroup {
  required: ParamDoc[];
  optional: ParamDoc[];
}

interface ParamDoc {
  name: string;
  type: string;
  descriptionLLM: string;
  descriptionHuman: string;
  whenPresent: string;
  whenAbsent: string | null;
  constraints: string[];
  examples: string[];
  enumValues: string[];
}

interface RejectedParam {
  name: string;
  reason: string;
}

interface FailureMode {
  condition: string;
  response: string;
}

interface ToolExample {
  title: string;
  params: Record<string, string>;
  outcome: string;
  notes: string | null;
}

interface Verdict {
  keep: VerdictReason | null;
  drop: VerdictReason | null;
}

interface VerdictReason {
  reasoning: string;
  severity: 'STRONG' | 'NORMAL' | 'WEAK';
}

interface AuditNote {
  kind: 'MERGE_OPPORTUNITY' | 'REMOVABLE_PARAM' | 'OBSERVATION' | 'DEPRECATION';
  text: string;
}

interface RelatedTool {
  name: string;
  relationship: 'ALTERNATIVE' | 'COMPLEMENT' | 'FALLBACK' | 'COMPOSE_WITH' | 'SEE_ALSO';
  note: string;
}

// ── Static lookup tables ─────────────────────────────────────────────────────

const SIDE_EFFECT_BADGE: Record<SideEffectKind, { label: string; bg: string; fg: string; hint: string }> = {
  READ_ONLY: { label: '◌ read-only', bg: 'rgba(86,211,100,0.12)', fg: '#56d364', hint: 'No filesystem, process, or IDE state mutation' },
  AGENT_CONTROL: { label: '◇ agent-only', bg: 'rgba(155,168,180,0.12)', fg: '#9ba8b4', hint: 'Mutates only agent state' },
  FILE_WRITE: { label: '✎ file-write', bg: 'rgba(227,179,65,0.12)', fg: '#e3b341', hint: 'Mutates files on disk' },
  PROCESS_SPAWN: { label: '⚙ process-spawn', bg: 'rgba(255,123,114,0.14)', fg: '#ff7b72', hint: 'Spawns or signals OS processes' },
  NETWORK: { label: '⇆ network', bg: 'rgba(108,176,224,0.12)', fg: '#6cb0e0', hint: 'Hits external network' },
  IDE_MUTATION: { label: '◐ ide-state', bg: 'rgba(210,168,255,0.14)', fg: '#d2a8ff', hint: 'Mutates IDE/JVM runtime state without writing files' },
};

const RELATIONSHIP_META: Record<RelatedTool['relationship'], { icon: string; label: string }> = {
  ALTERNATIVE: { icon: '⇄', label: 'alternative' },
  COMPLEMENT: { icon: '+', label: 'complement' },
  FALLBACK: { icon: '↩', label: 'fallback' },
  COMPOSE_WITH: { icon: '∘', label: 'compose with' },
  SEE_ALSO: { icon: '↗', label: 'see also' },
};

const AUDIT_BADGE: Record<AuditNote['kind'], { label: string; color: string; bg: string }> = {
  MERGE_OPPORTUNITY: { label: 'merge opportunity', color: '#d2a8ff', bg: 'rgba(210,168,255,0.12)' },
  REMOVABLE_PARAM: { label: 'removable param', color: '#e3b341', bg: 'rgba(227,179,65,0.12)' },
  OBSERVATION: { label: 'observation', color: '#6cb0e0', bg: 'rgba(108,176,224,0.12)' },
  DEPRECATION: { label: 'deprecated', color: '#f85149', bg: 'rgba(248,81,73,0.12)' },
};

// ── Helpers ──────────────────────────────────────────────────────────────────

function fmtTokens(n: number | null | undefined): string {
  if (n == null) return '?';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
  return String(n);
}

function tokenColor(n: number): string {
  if (n > 400) return '#f85149';
  if (n > 250) return '#e3b341';
  return '#56d364';
}

function verdictColor(v: Verdict): string {
  if (v.keep && !v.drop) return '#56d364';
  if (v.drop && !v.keep) return '#f85149';
  if (v.keep && v.drop) return '#e3b341';
  return '#6e7681';
}

function verdictGlyph(v: Verdict): string {
  if (v.keep && !v.drop) return '✓';
  if (v.drop && !v.keep) return '✗';
  if (v.keep && v.drop) return '⚖';
  return '?';
}

// ── Sub-components ───────────────────────────────────────────────────────────

const Badge = ({ children, bg, fg, title }: { children: React.ReactNode; bg: string; fg: string; title?: string }) => (
  <span
    title={title}
    className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium border"
    style={{ background: bg, color: fg, borderColor: fg + '4d' }}
  >
    {children}
  </span>
);

const SectionHeader = ({ children, count }: { children: React.ReactNode; count?: number | string }) => (
  <h2 className="text-xs uppercase tracking-wider font-semibold mb-3" style={{ color: 'var(--fg-muted,#6e7681)' }}>
    {children}
    {count !== undefined && <span className="ml-2 normal-case">({count})</span>}
  </h2>
);

const Card: React.FC<{ children: React.ReactNode; className?: string; style?: React.CSSProperties }> = ({
  children,
  className = '',
  style,
}) => (
  <div
    className={'rounded-lg p-4 ' + className}
    style={{ background: 'var(--bg-2,#161b22)', border: '1px solid var(--border-soft,#21262d)', ...style }}
  >
    {children}
  </div>
);

function SeverityDot({ severity }: { severity: VerdictReason['severity'] }) {
  const color = severity === 'STRONG' ? '#56d364' : severity === 'NORMAL' ? '#6cb0e0' : '#6e7681';
  const shadow = severity === 'STRONG' ? '0 0 0 2px rgba(86,211,100,0.2)' : 'none';
  return (
    <span
      className="inline-block rounded-full mr-1.5"
      style={{ width: 7, height: 7, background: color, boxShadow: shadow }}
    />
  );
}

function VerdictCard({ v, title }: { v: Verdict; title: string }) {
  if (!v.keep && !v.drop) {
    return (
      <Card style={{ borderStyle: 'dashed' }}>
        <div className="text-sm" style={{ color: '#e3b341' }}>
          No verdict — needs attention
        </div>
        <div className="text-xs mt-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
          The author hasn't decided whether {title} should stay or go.
        </div>
      </Card>
    );
  }
  const borderColor = v.keep && v.drop ? '#e3b341' : v.keep ? '#56d364' : '#f85149';
  return (
    <Card style={{ borderLeft: `3px solid ${borderColor}` }}>
      {v.keep && (
        <div className={v.drop ? 'mb-3' : ''}>
          <div className="flex items-center gap-2 mb-1.5">
            <span className="text-xs uppercase font-semibold tracking-wider" style={{ color: '#56d364' }}>
              ✓ Keep
            </span>
            <Badge bg="rgba(86,211,100,0.12)" fg="#56d364">
              <SeverityDot severity={v.keep.severity} />
              {v.keep.severity.toLowerCase()}
            </Badge>
          </div>
          <div className="text-sm">{v.keep.reasoning}</div>
        </div>
      )}
      {v.drop && (
        <div>
          <div className="flex items-center gap-2 mb-1.5">
            <span className="text-xs uppercase font-semibold tracking-wider" style={{ color: '#f85149' }}>
              ✗ Drop
            </span>
            <Badge bg="rgba(248,81,73,0.12)" fg="#f85149">
              <SeverityDot severity={v.drop.severity} />
              {v.drop.severity.toLowerCase()}
            </Badge>
          </div>
          <div className="text-sm">{v.drop.reasoning}</div>
        </div>
      )}
    </Card>
  );
}

function ParamCard({ p }: { p: ParamDoc; kind: 'required' | 'optional' }) {
  return (
    <Card>
      <div className="flex items-baseline gap-3 mb-2">
        <code className="text-base font-semibold font-mono" style={{ color: 'var(--fg,#e6edf3)' }}>
          {p.name}
        </code>
        <span className="text-xs font-mono" style={{ color: 'var(--fg-muted,#6e7681)' }}>
          {p.type}
        </span>
      </div>
      <div className="grid grid-cols-2 gap-3 mt-3">
        <div>
          <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            What the LLM sees
          </div>
          <div
            className="text-xs font-mono"
            style={{ color: 'var(--fg-secondary,#9ba8b4)', padding: '6px 8px', background: 'var(--bg,#0f1419)', borderRadius: 4 }}
          >
            {p.descriptionLLM}
          </div>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            Plain English
          </div>
          <div className="text-sm" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
            {p.descriptionHuman}
          </div>
        </div>
      </div>
      <div className="mt-3 text-sm">
        <span style={{ color: '#56d364' }}>When present: </span>
        <span style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>{p.whenPresent}</span>
      </div>
      {p.whenAbsent && (
        <div className="mt-1 text-sm">
          <span style={{ color: '#6cb0e0' }}>When absent: </span>
          <span style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>{p.whenAbsent}</span>
        </div>
      )}
      {p.constraints.length > 0 && (
        <div className="mt-3">
          <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            Constraints
          </div>
          <ul className="text-xs pl-5 list-disc" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
            {p.constraints.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </div>
      )}
      {p.examples.length > 0 && (
        <div className="mt-3">
          <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            Examples
          </div>
          <div className="flex flex-wrap gap-2">
            {p.examples.map((e, i) => (
              <code
                key={i}
                className="font-mono text-xs"
                style={{ color: '#d2a8ff', padding: '2px 8px', background: 'var(--bg,#0f1419)', borderRadius: 3 }}
              >
                {e}
              </code>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}

function RejectedParamCard({ p }: { p: RejectedParam }) {
  return (
    <Card>
      <div className="flex items-baseline gap-3 mb-2">
        <code className="text-base font-semibold font-mono">{p.name}</code>
        <Badge bg="rgba(155,168,180,0.08)" fg="var(--fg-muted,#6e7681)">
          rejected
        </Badge>
      </div>
      <div className="text-sm" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
        {p.reason}
      </div>
    </Card>
  );
}

function ActionCard({ a, defaultOpen }: { a: ActionDoc; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const totalParams = a.requiredParams.length + a.optionalParams.length;

  return (
    <details
      open={open}
      onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}
      className="mb-2"
    >
      <summary
        className="rounded-lg p-4 flex items-center gap-3 cursor-pointer list-none"
        style={{ background: 'var(--bg-2,#161b22)', border: '1px solid var(--border-soft,#21262d)' }}
      >
        <span style={{ color: 'var(--fg-muted,#6e7681)', fontSize: 12, transform: open ? 'rotate(90deg)' : '', transition: 'transform 0.15s' }}>
          ▶
        </span>
        <span style={{ fontSize: 16, color: verdictColor(a.verdict), fontWeight: 700 }}>{verdictGlyph(a.verdict)}</span>
        <code className="font-mono font-semibold text-base flex-1">{a.name}</code>
        <span className="text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
          {totalParams} params · {a.onFailure.length} failure modes
        </span>
      </summary>
      <div className="mt-3 ml-3 pl-5" style={{ borderLeft: '2px solid var(--border-soft,#21262d)' }}>
        {/* Description */}
        <Card className="mb-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                Technical
              </div>
              <div>{a.description.technical}</div>
            </div>
            <div>
              <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                Plain English
              </div>
              <div>{a.description.plain}</div>
            </div>
          </div>
          {a.whenLLMUses && (
            <div
              className="mt-4 p-3 rounded text-sm"
              style={{ background: 'var(--bg,#0f1419)', borderLeft: '3px solid #6cb0e0' }}
            >
              <div className="text-[10px] uppercase tracking-wider font-semibold mb-1" style={{ color: '#6cb0e0' }}>
                When the LLM uses this
              </div>
              <div style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>{a.whenLLMUses}</div>
            </div>
          )}
        </Card>

        {/* Preconditions */}
        {a.preconditions.length > 0 && (
          <Card className="mb-3" style={{ borderLeft: '3px solid #e3b341' }}>
            <div
              className="text-xs font-semibold uppercase tracking-wider mb-2"
              style={{ color: '#e3b341' }}
            >
              ⚠ Preconditions
            </div>
            <ul className="text-sm pl-5 list-disc" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
              {a.preconditions.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ul>
          </Card>
        )}

        {/* Params */}
        {a.requiredParams.length > 0 && (
          <div className="mb-3">
            <SectionHeader count={a.requiredParams.length}>Required parameters</SectionHeader>
            <div className="space-y-2">
              {a.requiredParams.map((p) => (
                <ParamCard key={p.name} p={p} kind="required" />
              ))}
            </div>
          </div>
        )}
        {a.optionalParams.length > 0 && (
          <div className="mb-3">
            <SectionHeader count={a.optionalParams.length}>Optional parameters</SectionHeader>
            <div className="space-y-2">
              {a.optionalParams.map((p) => (
                <ParamCard key={p.name} p={p} kind="optional" />
              ))}
            </div>
          </div>
        )}
        {a.rejectedParams.length > 0 && (
          <div className="mb-3">
            <SectionHeader count={a.rejectedParams.length}>Rejected parameters</SectionHeader>
            <div className="space-y-2">
              {a.rejectedParams.map((p) => (
                <RejectedParamCard key={p.name} p={p} />
              ))}
            </div>
          </div>
        )}

        {/* Outcomes */}
        <Card className="mb-3">
          <div className="text-xs font-semibold uppercase tracking-wider mb-2" style={{ color: '#56d364' }}>
            ✓ On success
          </div>
          <div className="text-sm">{a.onSuccess}</div>
        </Card>
        {a.onFailure.length > 0 && (
          <Card className="mb-3">
            <div className="text-xs font-semibold uppercase tracking-wider mb-2" style={{ color: '#f85149' }}>
              ✗ Failure modes ({a.onFailure.length})
            </div>
            <div className="space-y-2">
              {a.onFailure.map((f, i) => (
                <div key={i} className="text-sm">
                  <span
                    className="font-mono px-2 py-0.5 rounded text-xs"
                    style={{ background: 'rgba(248,81,73,0.12)', color: '#f85149' }}
                  >
                    {f.condition}
                  </span>
                  <span className="ml-2" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
                    {f.response}
                  </span>
                </div>
              ))}
            </div>
          </Card>
        )}

        {/* Examples */}
        {a.examples.length > 0 && (
          <Card className="mb-3">
            <SectionHeader>Examples</SectionHeader>
            <div className="space-y-3">
              {a.examples.map((ex, i) => (
                <div key={i}>
                  <div className="text-sm font-semibold mb-2">{ex.title}</div>
                  <div className="p-2 rounded mb-2" style={{ background: 'var(--bg,#0f1419)' }}>
                    {Object.entries(ex.params).map(([k, v]) => (
                      <div key={k} className="flex gap-3 text-xs font-mono">
                        <span style={{ color: '#6cb0e0', minWidth: 80 }}>{k}</span>
                        <span style={{ color: '#d2a8ff' }}>{v}</span>
                      </div>
                    ))}
                  </div>
                  <div className="text-xs" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
                    → {ex.outcome}
                  </div>
                </div>
              ))}
            </div>
          </Card>
        )}

        <VerdictCard v={a.verdict} title={a.name} />
      </div>
    </details>
  );
}

// Mermaid renderer — uses npm-bundled mermaid via dynamic .run() each mount.
function MermaidDiagram({ source, id }: { source: string; id: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    let cancelled = false;
    mermaid.initialize({
      startOnLoad: false,
      theme: 'dark',
      themeVariables: {
        primaryColor: '#1f262e',
        primaryTextColor: '#e6edf3',
        primaryBorderColor: '#30363d',
        lineColor: '#6cb0e0',
        secondaryColor: '#0f1419',
        tertiaryColor: '#161b22',
      },
    });
    mermaid
      .render(id, source)
      .then(({ svg }) => {
        if (!cancelled && ref.current) ref.current.innerHTML = svg;
      })
      .catch((err) => {
        if (!cancelled && ref.current) {
          ref.current.innerHTML = `<div style="color:#f85149;font-size:12px">mermaid render error: ${err.message}</div>`;
        }
      });
    return () => {
      cancelled = true;
    };
  }, [source, id]);
  return (
    <div
      className="rounded-lg p-6 overflow-auto"
      style={{ background: 'var(--bg,#0f1419)', border: '1px solid var(--border-soft,#21262d)', maxHeight: 600 }}
    >
      <div ref={ref} className="flex justify-center" />
    </div>
  );
}

// ── Main view ────────────────────────────────────────────────────────────────

export function ToolDocView({ doc }: { doc: ToolDocPayload }) {
  const [summaryTab, setSummaryTab] = useState<'technical' | 'plain'>('technical');
  const seBadge = SIDE_EFFECT_BADGE[doc.sideEffect];

  return (
    <div className="min-h-screen overflow-y-auto" style={{ background: 'var(--bg,#0f1419)', color: 'var(--fg,#e6edf3)' }}>
      {/* Header */}
      <div
        className="px-8 py-6"
        style={{
          borderBottom: '1px solid var(--border-soft,#21262d)',
          background: 'linear-gradient(180deg, var(--bg-2,#161b22) 0%, var(--bg,#0f1419) 100%)',
        }}
      >
        <div className="flex items-center gap-3 mb-2 flex-wrap">
          <Badge
            bg={doc.tier === 'Core' ? 'rgba(108,176,224,0.12)' : 'rgba(155,168,180,0.12)'}
            fg={doc.tier === 'Core' ? '#6cb0e0' : '#9ba8b4'}
          >
            {doc.tier}
          </Badge>
          <Badge bg="rgba(155,168,180,0.12)" fg="#9ba8b4">
            {doc.actions ? `${doc.actions.length} actions` : 'single-action'}
          </Badge>
          {seBadge && (
            <Badge bg={seBadge.bg} fg={seBadge.fg} title={seBadge.hint}>
              {seBadge.label}
            </Badge>
          )}
          <span className="text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            Tool documentation
          </span>
        </div>
        <h1 className="font-mono font-bold" style={{ fontSize: 28 }}>
          {doc.toolName}
        </h1>
        <div className="mt-3">
          <div className="flex gap-2 mb-2">
            {(['technical', 'plain'] as const).map((tab) => (
              <button
                key={tab}
                onClick={() => setSummaryTab(tab)}
                className="px-3 py-1.5 rounded-md text-[13px] font-medium transition-colors"
                style={{
                  background: summaryTab === tab ? 'var(--bg-3,#1f262e)' : 'transparent',
                  color: summaryTab === tab ? 'var(--fg,#e6edf3)' : 'var(--fg-secondary,#9ba8b4)',
                  cursor: 'pointer',
                  border: 'none',
                }}
              >
                {tab === 'technical' ? 'Technical' : 'Plain English'}
              </button>
            ))}
          </div>
          <div className="text-base">
            {summaryTab === 'technical' ? doc.summary.technical : doc.summary.plain}
          </div>
        </div>
      </div>

      <div className="px-8 py-6" style={{ maxWidth: 1100 }}>
        {/* Capability strip */}
        <section className="mb-6">
          <div className="text-xs uppercase tracking-wider font-semibold mb-2" style={{ color: 'var(--fg-muted,#6e7681)' }}>
            Auto-derived from source
          </div>
          <div
            className="rounded-lg p-3 flex flex-wrap gap-x-3 gap-y-2 text-xs"
            style={{ background: 'var(--bg-2,#161b22)', border: '1px solid var(--border-soft,#21262d)' }}
          >
            <CapCell label="Schema cost" valueColor={tokenColor(doc.metadata.schemaTokenCost)}>
              ~{fmtTokens(doc.metadata.schemaTokenCost)} tok
            </CapCell>
            <CapDivider />
            <CapCell label="Registration">{doc.metadata.registrationCondition}</CapCell>
            <CapDivider />
            <CapCell label="Approval" mono>
              {doc.metadata.approvalPolicy}
            </CapCell>
            <CapDivider />
            <CapCell label="Plan mode" valueColor={doc.metadata.planModeBlocked ? '#f85149' : '#56d364'}>
              {doc.metadata.planModeBlocked ? 'BLOCKED' : 'allowed'}
            </CapCell>
            <CapDivider />
            <CapCell label="Workers" mono>
              {doc.metadata.allowedWorkers.join(' ')}
            </CapCell>
            <CapDivider />
            <CapCell label="Timeout">{doc.metadata.timeoutClass}</CapCell>
            <CapDivider />
            <CapCell label="Output cap">{doc.metadata.outputCap}</CapCell>
          </div>
        </section>

        {/* What the LLM sees */}
        <section className="mb-8">
          <SectionHeader>What the LLM sees</SectionHeader>
          <Card>
            <div className="text-xs mb-2 flex items-center gap-2" style={{ color: 'var(--fg-muted,#6e7681)' }}>
              <span className="font-mono px-1.5 py-0.5 rounded" style={{ background: 'var(--bg-3,#1f262e)' }}>
                description
              </span>
              <span>— sent into the function-calling schema, not auto-derived from the docs above</span>
            </div>
            <pre
              className="font-mono text-xs whitespace-pre-wrap p-3 rounded"
              style={{
                background: 'var(--bg,#0f1419)',
                border: '1px solid var(--border-soft,#21262d)',
                color: 'var(--fg-secondary,#9ba8b4)',
                maxHeight: 200,
                overflowY: 'auto',
              }}
            >
              {doc.whatLLMSees}
            </pre>
          </Card>
        </section>

        {/* Tool-level flowchart */}
        {doc.flowchart && (
          <section className="mb-8">
            <SectionHeader>Flow diagram</SectionHeader>
            <MermaidDiagram source={doc.flowchart} id={`mermaid-tool-${doc.toolName}`} />
          </section>
        )}

        {/* Single-action params */}
        {doc.singleActionParams && (
          <section className="mb-8">
            <SectionHeader count={doc.singleActionParams.required.length + doc.singleActionParams.optional.length}>
              Parameters
            </SectionHeader>
            {doc.singleActionParams.required.length > 0 && (
              <>
                <div className="text-xs uppercase tracking-wider font-semibold mb-2" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                  Required ({doc.singleActionParams.required.length})
                </div>
                <div className="space-y-2 mb-4">
                  {doc.singleActionParams.required.map((p) => (
                    <ParamCard key={p.name} p={p} kind="required" />
                  ))}
                </div>
              </>
            )}
            {doc.singleActionParams.optional.length > 0 && (
              <>
                <div className="text-xs uppercase tracking-wider font-semibold mb-2" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                  Optional ({doc.singleActionParams.optional.length})
                </div>
                <div className="space-y-2">
                  {doc.singleActionParams.optional.map((p) => (
                    <ParamCard key={p.name} p={p} kind="optional" />
                  ))}
                </div>
              </>
            )}
          </section>
        )}

        {/* Actions */}
        {doc.actions && doc.actions.length > 0 && (
          <section className="mb-8">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-xs uppercase tracking-wider font-semibold" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                Actions ({doc.actions.length})
              </h2>
              <div className="flex gap-3 text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                <span>
                  ✓ keep <span style={{ color: '#56d364' }}>{doc.actions.filter((a) => a.verdict.keep && !a.verdict.drop).length}</span>
                </span>
                <span>
                  ⚖ mixed <span style={{ color: '#e3b341' }}>{doc.actions.filter((a) => a.verdict.keep && a.verdict.drop).length}</span>
                </span>
                <span>
                  ✗ drop <span style={{ color: '#f85149' }}>{doc.actions.filter((a) => a.verdict.drop && !a.verdict.keep).length}</span>
                </span>
              </div>
            </div>
            <div>
              {doc.actions.map((a, i) => (
                <ActionCard key={a.name} a={a} defaultOpen={i === 0} />
              ))}
            </div>
          </section>
        )}

        {/* Tool-level verdict + counterfactual */}
        <section className="mb-8">
          <SectionHeader>Tool-level verdict</SectionHeader>
          <VerdictCard v={doc.toolVerdict} title={doc.toolName} />
          {doc.counterfactual && (
            <div
              className="mt-3 rounded-lg p-4"
              style={{
                background: 'linear-gradient(135deg, rgba(227,179,65,0.08) 0%, rgba(227,179,65,0.02) 100%)',
                border: '1px solid rgba(227,179,65,0.3)',
              }}
            >
              <div className="flex items-center gap-2 mb-1.5">
                <span className="text-xs uppercase font-semibold tracking-wider" style={{ color: '#e3b341' }}>
                  ⚠ If dropped — counterfactual
                </span>
                <span className="text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                  what the LLM would do instead
                </span>
              </div>
              <div className="text-sm">{doc.counterfactual}</div>
            </div>
          )}
        </section>

        {/* Common LLM mistakes */}
        {doc.commonLLMMistakes.length > 0 && (
          <section className="mb-8">
            <div className="flex items-baseline gap-3 mb-3">
              <h2 className="text-xs uppercase tracking-wider font-semibold" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                Common LLM mistakes ({doc.commonLLMMistakes.length})
              </h2>
              <span className="text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                — patterns the LLM screws up
              </span>
            </div>
            <div className="space-y-2">
              {doc.commonLLMMistakes.map((m, i) => (
                <div
                  key={i}
                  className="p-3 rounded text-sm flex gap-3"
                  style={{ background: 'var(--bg-2,#161b22)', borderLeft: '3px solid #ff7b72' }}
                >
                  <span
                    className="font-mono text-xs font-semibold flex-shrink-0 self-start"
                    style={{ color: '#ff7b72', background: 'rgba(255,123,114,0.08)', padding: '2px 7px', borderRadius: 3 }}
                  >
                    #{i + 1}
                  </span>
                  <span className="flex-1">{m}</span>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Audit notes */}
        {doc.auditNotes.length > 0 && (
          <section className="mb-8">
            <SectionHeader count={doc.auditNotes.length}>Audit notes</SectionHeader>
            <div className="space-y-2">
              {doc.auditNotes.map((n, i) => {
                const meta = AUDIT_BADGE[n.kind];
                return (
                  <Card key={i} className="flex gap-3">
                    <Badge bg={meta.bg} fg={meta.color}>
                      {meta.label}
                    </Badge>
                    <div className="text-sm">{n.text}</div>
                  </Card>
                );
              })}
            </div>
          </section>
        )}

        {/* Related tools */}
        {doc.relatedTools.length > 0 && (
          <section className="mb-8">
            <SectionHeader count={doc.relatedTools.length}>Related tools</SectionHeader>
            <div className="grid grid-cols-2 gap-2">
              {doc.relatedTools.map((r) => {
                const meta = RELATIONSHIP_META[r.relationship];
                return (
                  <Card key={r.name} className="flex gap-3 items-start">
                    <span
                      title={meta.label}
                      className="inline-flex items-center justify-center text-xs"
                      style={{
                        width: 22,
                        height: 22,
                        borderRadius: 4,
                        background: 'var(--bg-3,#1f262e)',
                        color: 'var(--fg-secondary,#9ba8b4)',
                      }}
                    >
                      {meta.icon}
                    </span>
                    <div className="flex-1">
                      <div className="flex items-baseline gap-2">
                        <code className="font-mono font-semibold text-sm">{r.name}</code>
                        <span className="text-xs" style={{ color: 'var(--fg-muted,#6e7681)' }}>
                          {meta.label}
                        </span>
                      </div>
                      <div className="text-xs mt-1" style={{ color: 'var(--fg-secondary,#9ba8b4)' }}>
                        {r.note}
                      </div>
                    </div>
                  </Card>
                );
              })}
            </div>
          </section>
        )}

        {/* Downsides */}
        {doc.downsides.length > 0 && (
          <section className="mb-8">
            <SectionHeader>Downsides &amp; gotchas</SectionHeader>
            <Card style={{ borderLeft: '3px solid #e3b341' }}>
              <ul className="space-y-2 text-sm">
                {doc.downsides.map((d, i) => (
                  <li key={i} className="flex gap-2">
                    <span style={{ color: '#e3b341', flexShrink: 0 }}>◆</span>
                    <span>{d}</span>
                  </li>
                ))}
              </ul>
            </Card>
          </section>
        )}

        {/* Narrative */}
        {doc.narrative && (
          <section className="mb-12">
            <SectionHeader>Extended narrative</SectionHeader>
            <Card className="p-6">
              <div className="prose prose-invert prose-sm max-w-none" style={{ color: 'var(--fg,#e6edf3)' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{doc.narrative}</ReactMarkdown>
              </div>
            </Card>
          </section>
        )}
      </div>
    </div>
  );
}

// Capability strip helper components
function CapCell({
  label,
  children,
  mono,
  valueColor,
}: {
  label: string;
  children: React.ReactNode;
  mono?: boolean;
  valueColor?: string;
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="uppercase tracking-wider text-[10px] font-semibold" style={{ color: 'var(--fg-muted,#6e7681)' }}>
        {label}
      </span>
      <span
        className={mono ? 'font-mono' : ''}
        style={{ fontWeight: 500, color: valueColor || 'var(--fg,#e6edf3)' }}
      >
        {children}
      </span>
    </div>
  );
}
function CapDivider() {
  return <span style={{ color: 'var(--border,#30363d)' }}>·</span>;
}
