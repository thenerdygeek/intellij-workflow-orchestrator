import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ApiDocView, type ApiDocPayload } from '../ApiDocView';

const fixture: ApiDocPayload = {
  families: [{
    id: 'jira', displayName: 'Jira (Data Center)',
    authScheme: 'Bearer <PAT>', probedServerVersion: 'Jira DC 10.3.16',
    description: 'Jira REST v2.',
    categories: [{
      name: 'Identity',
      endpoints: [{
        method: 'GET', pathTemplate: '/rest/api/2/myself', status: 'USED',
        summary: 'Current user.', provenance: 'probe Result_Jira/raw/myself.json',
        callSite: 'JiraApiClient.kt:142',
      }],
    }],
  }],
  loadErrors: [],
};

describe('ApiDocView', () => {
  it('renders a family tab, an endpoint, its status badge and provenance', () => {
    render(<ApiDocView doc={fixture} />);
    expect(screen.getAllByText('Jira (Data Center)').length).toBeGreaterThan(0);
    expect(screen.getByText('/rest/api/2/myself')).toBeTruthy();
    expect(screen.getByText('USED')).toBeTruthy();
    expect(screen.getByText(/probe Result_Jira\/raw\/myself\.json/)).toBeTruthy();
  });

  it('shows a friendly message when no families load', () => {
    render(<ApiDocView doc={{ families: [], loadErrors: [{ id: 'jira', error: 'missing' }] }} />);
    expect(screen.getByText(/No API documentation loaded/)).toBeTruthy();
  });
});
