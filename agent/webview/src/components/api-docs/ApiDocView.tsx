import { useState } from 'react';

// ── Wire-format types (mirror Kotlin ApiDocPayload) ──────────────────────────

export type ApiEndpointStatus = 'USED' | 'PROBED_UNUSED' | 'KNOWN_UNUSED' | 'DEPRECATED';
export type ApiParamLocation = 'QUERY' | 'PATH' | 'BODY' | 'HEADER';

export interface ApiParam {
  name: string;
  location: ApiParamLocation;
  type: string;
  required: boolean;
  description: string;
  example?: string | null;
}
export interface ApiVerdict { classification: string; reasoning: string; }
export interface ApiEndpoint {
  method: string;
  pathTemplate: string;
  status: ApiEndpointStatus;
  summary: string;
  params?: ApiParam[];
  requestBody?: string | null;
  sampleResponse?: string | null;
  callSite?: string | null;
  provenance: string;
  verdict?: ApiVerdict | null;
  gotchas?: string[];
}
export interface ApiCategory { name: string; endpoints: ApiEndpoint[]; }
export interface ApiFamily {
  id: string;
  displayName: string;
  authScheme: string;
  probedServerVersion: string;
  description: string;
  categories: ApiCategory[];
}
export interface ApiDocLoadError { id: string; error: string; }
export interface ApiDocPayload { families: ApiFamily[]; loadErrors: ApiDocLoadError[]; }

const STATUS_COLOR: Record<ApiEndpointStatus, string> = {
  USED: 'var(--success,#16A34A)',
  PROBED_UNUSED: 'var(--accent,#2563EB)',
  KNOWN_UNUSED: 'var(--fg-muted,#64748B)',
  DEPRECATED: 'var(--warning,#D97706)',
};
const STATUS_LABEL: Record<ApiEndpointStatus, string> = {
  USED: 'USED',
  PROBED_UNUSED: 'PROBED · UNUSED',
  KNOWN_UNUSED: 'KNOWN · UNUSED',
  DEPRECATED: 'DEPRECATED',
};

function Badge({ status }: { status: ApiEndpointStatus }) {
  return (
    <span style={{
      fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 10,
      color: STATUS_COLOR[status], border: `1px solid ${STATUS_COLOR[status]}`,
    }}>{STATUS_LABEL[status]}</span>
  );
}

function EndpointCard({ ep }: { ep: ApiEndpoint }) {
  const [showSample, setShowSample] = useState(false);
  return (
    <div style={{
      border: '1px solid var(--border,#E2E8F0)', borderRadius: 8,
      padding: 12, marginBottom: 10,
    }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <code style={{ fontWeight: 700 }}>{ep.method}</code>
        <code style={{ color: 'var(--fg-secondary,#475569)' }}>{ep.pathTemplate}</code>
        <Badge status={ep.status} />
      </div>
      <div style={{ marginTop: 6, fontSize: 13 }}>{ep.summary}</div>

      {ep.params && ep.params.length > 0 && (
        <table style={{ marginTop: 8, fontSize: 12, width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ color: 'var(--fg-muted,#64748B)', textAlign: 'left' }}>
              <th>Param</th><th>In</th><th>Type</th><th>Req</th><th>Description</th>
            </tr>
          </thead>
          <tbody>
            {ep.params.map((p) => (
              <tr key={p.name} style={{ borderTop: '1px solid var(--border,#E2E8F0)' }}>
                <td><code>{p.name}</code></td>
                <td>{p.location}</td>
                <td>{p.type}</td>
                <td>{p.required ? 'yes' : 'no'}</td>
                <td>{p.description}{p.example ? ` (e.g. ${p.example})` : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {ep.requestBody && (
        <div style={{ marginTop: 8, fontSize: 12 }}>
          <strong>Request body:</strong> {ep.requestBody}
        </div>
      )}

      {ep.gotchas && ep.gotchas.length > 0 && (
        <ul style={{ marginTop: 8, fontSize: 12, color: 'var(--warning,#D97706)' }}>
          {ep.gotchas.map((g, i) => <li key={i}>{g}</li>)}
        </ul>
      )}

      {ep.verdict && (
        <div style={{
          marginTop: 8, fontSize: 12, padding: 8, borderRadius: 6,
          background: 'var(--code-bg,#F1F5F9)',
        }}>
          <strong>{ep.verdict.classification}</strong> — {ep.verdict.reasoning}
        </div>
      )}

      {ep.sampleResponse && (
        <div style={{ marginTop: 8 }}>
          <button onClick={() => setShowSample((s) => !s)} style={{
            fontSize: 12, color: 'var(--accent,#2563EB)', background: 'none',
            border: 'none', cursor: 'pointer', padding: 0,
          }}>{showSample ? '▾ Hide sample response' : '▸ Show sample response'}</button>
          {showSample && (
            <pre style={{
              marginTop: 6, fontSize: 11, overflowX: 'auto', padding: 8,
              borderRadius: 6, background: 'var(--code-bg,#F1F5F9)',
            }}>{ep.sampleResponse}</pre>
          )}
        </div>
      )}

      <div style={{ marginTop: 8, fontSize: 11, color: 'var(--fg-muted,#64748B)' }}>
        {ep.callSite ? <>Call site: <code>{ep.callSite}</code> · </> : null}
        Source: {ep.provenance}
      </div>
    </div>
  );
}

export function ApiDocView({ doc }: { doc: ApiDocPayload }) {
  const [active, setActive] = useState(0);
  const families = doc.families;
  if (families.length === 0) {
    return <div style={{ padding: 24, color: 'var(--fg-muted,#64748B)' }}>
      No API documentation loaded.
      {doc.loadErrors.map((e) => <div key={e.id}>· {e.id}: {e.error}</div>)}
    </div>;
  }
  const fam = families[Math.min(active, families.length - 1)]!;
  return (
    <div style={{ padding: 16, color: 'var(--fg,#1E293B)' }}>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 14 }}>
        {families.map((f, i) => (
          <button key={f.id} onClick={() => setActive(i)} style={{
            padding: '4px 12px', borderRadius: 8, cursor: 'pointer',
            border: '1px solid var(--border,#E2E8F0)',
            background: i === active ? 'var(--accent,#2563EB)' : 'transparent',
            color: i === active ? '#fff' : 'var(--fg,#1E293B)',
          }}>{f.displayName}</button>
        ))}
      </div>

      {doc.loadErrors.length > 0 && (
        <div style={{ marginBottom: 12, fontSize: 12, color: 'var(--error,#DC2626)' }}>
          {doc.loadErrors.map((e) => <div key={e.id}>Failed to load {e.id}: {e.error}</div>)}
        </div>
      )}

      <h2 style={{ margin: '0 0 4px' }}>{fam.displayName}</h2>
      <div style={{ fontSize: 12, color: 'var(--fg-muted,#64748B)', marginBottom: 4 }}>
        Auth: <code>{fam.authScheme}</code> · Probed: {fam.probedServerVersion}
      </div>
      <div style={{ fontSize: 13, marginBottom: 16 }}>{fam.description}</div>

      {fam.categories.map((cat) => (
        <section key={cat.name} style={{ marginBottom: 20 }}>
          <h3 style={{ borderBottom: '1px solid var(--border,#E2E8F0)', paddingBottom: 4 }}>
            {cat.name}
          </h3>
          {cat.endpoints.map((ep, i) => <EndpointCard key={`${ep.method}-${ep.pathTemplate}-${i}`} ep={ep} />)}
        </section>
      ))}
    </div>
  );
}
