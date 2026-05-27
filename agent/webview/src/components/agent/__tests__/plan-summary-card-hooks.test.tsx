/**
 * #4: PlanSummaryCard previously did `if (plan.approved) return null;` BEFORE its
 * hooks. When the same instance re-renders with approved flipping false→true the
 * hook count changes → React throws "rendered fewer hooks than expected". The
 * early-return now lives after all hooks.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import { PlanSummaryCard } from '../PlanSummaryCard';

afterEach(() => {
  document.body.innerHTML = '';
});

const plan = (approved: boolean) => ({ title: 'My Plan', summary: 'do things', steps: [], approved }) as never;

describe('PlanSummaryCard hook safety', () => {
  it('renders the card when not approved and null when approved', () => {
    const { container, rerender } = render(<PlanSummaryCard plan={plan(false)} />);
    expect(container.querySelector('[aria-label="Plan summary"]')).not.toBeNull();
    rerender(<PlanSummaryCard plan={plan(true)} />);
    expect(container.querySelector('[aria-label="Plan summary"]')).toBeNull();
  });

  it('does not throw when the same instance flips approved false → true', () => {
    const { rerender } = render(<PlanSummaryCard plan={plan(false)} />);
    expect(() => rerender(<PlanSummaryCard plan={plan(true)} />)).not.toThrow();
  });
});
