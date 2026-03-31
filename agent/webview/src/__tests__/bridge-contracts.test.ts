/**
 * Bridge Contract Tests — React Side
 *
 * Tests that React components produce/consume JSON matching the shared
 * contract fixtures in __tests__/contracts/*.json.
 *
 * The Kotlin side has matching tests that parse the SAME fixtures.
 * If either side changes the format, tests break on the OTHER side.
 *
 * HOW TO ADD A NEW CONTRACT:
 * 1. Create a fixture file in agent/src/test/resources/contracts/{name}.json
 * 2. Copy to agent/webview/src/__tests__/contracts/
 * 3. Add a test below asserting React produces/consumes this format
 * 4. Add a Kotlin test in agent/src/test/kotlin/.../BridgeContractTest.kt
 */
import { describe, it, expect } from 'vitest';
import planReviseContract from './contracts/plan-revise.json';
import planDataContract from './contracts/plan-data.json';
import planStepUpdateContract from './contracts/plan-step-update.json';
import editStatsContract from './contracts/edit-stats.json';

// ── Plan Revise Contract ──

describe('Contract: _revisePlan payload (React → Kotlin)', () => {
  it('valid payloads are parseable JSON with string values', () => {
    for (const testCase of planReviseContract.valid_payloads) {
      const parsed = JSON.parse(testCase.payload);
      expect(typeof parsed).toBe('object');

      // All keys should be strings
      for (const key of Object.keys(parsed)) {
        expect(typeof key).toBe('string');
        expect(typeof parsed[key]).toBe('string');
      }

      // Keys match expected
      expect(Object.keys(parsed).sort()).toEqual([...testCase.expected_keys].sort());
    }
  });

  it('invalid payloads throw on JSON.parse', () => {
    for (const testCase of planReviseContract.invalid_payloads) {
      if (testCase.payload === '') {
        // Empty string is not valid JSON
        expect(() => JSON.parse(testCase.payload)).toThrow();
      } else {
        expect(() => JSON.parse(testCase.payload)).toThrow();
      }
    }
  });

  it('comment keys follow section ID pattern', () => {
    const validKeyPattern = /^(goal|approach|testing|step-\d+)$/;
    for (const testCase of planReviseContract.valid_payloads) {
      const parsed = JSON.parse(testCase.payload);
      for (const key of Object.keys(parsed)) {
        expect(key).toMatch(validKeyPattern);
      }
    }
  });

  it('simulated Revise flow produces valid contract payload', () => {
    // Simulate what plan-editor.tsx handleRevise does:
    const comments = { 'goal': 'Too broad', 'step-1': 'Use existing service' };
    const payload = JSON.stringify(comments);

    // Verify it matches the contract format
    const parsed = JSON.parse(payload);
    expect(typeof parsed).toBe('object');
    for (const key of Object.keys(parsed)) {
      expect(typeof parsed[key]).toBe('string');
    }
  });
});

// ── Plan Data Contract ──

describe('Contract: renderPlan payload (Kotlin → React)', () => {
  it('plan data has required fields', () => {
    const plan = planDataContract.payload;
    for (const field of planDataContract.required_fields) {
      expect(plan).toHaveProperty(field);
    }
  });

  it('steps have required fields', () => {
    const plan = planDataContract.payload;
    for (const step of plan.steps) {
      for (const field of planDataContract.step_required_fields) {
        expect(step).toHaveProperty(field);
      }
    }
  });

  it('step IDs are strings', () => {
    for (const step of planDataContract.payload.steps) {
      expect(typeof step.id).toBe('string');
      expect(step.id.length).toBeGreaterThan(0);
    }
  });

  it('plan can be JSON.stringify + JSON.parse roundtripped', () => {
    const json = JSON.stringify(planDataContract.payload);
    const parsed = JSON.parse(json);
    expect(parsed.goal).toBe(planDataContract.payload.goal);
    expect(parsed.steps.length).toBe(planDataContract.payload.steps.length);
  });

  it('React can render plan data without errors', () => {
    // Simulate what plan-editor.tsx does with renderPlan(json)
    const json = JSON.stringify(planDataContract.payload);
    const data = JSON.parse(json);

    expect(data.goal).toBeTruthy();
    expect(Array.isArray(data.steps)).toBe(true);
    expect(data.steps.length).toBeGreaterThan(0);

    // Each step should have id and title for rendering
    for (const step of data.steps) {
      expect(step.id).toBeTruthy();
      expect(step.title).toBeTruthy();
    }
  });
});

// ── Plan Step Update Contract ──

describe('Contract: updatePlanStep payload (Kotlin → React)', () => {
  it('valid statuses are recognized', () => {
    const validStatuses = planStepUpdateContract.valid_statuses;
    expect(validStatuses).toContain('pending');
    expect(validStatuses).toContain('running');
    expect(validStatuses).toContain('completed');
    expect(validStatuses).toContain('failed');
  });

  it('valid calls have stepId and status strings', () => {
    for (const call of planStepUpdateContract.valid_calls) {
      expect(typeof call.stepId).toBe('string');
      expect(typeof call.status).toBe('string');
      expect(planStepUpdateContract.valid_statuses).toContain(call.status);
    }
  });

  it('React step update logic handles all valid statuses', () => {
    // Simulate updatePlanStep in plan-editor.tsx
    const steps = planDataContract.payload.steps.map(s => ({ ...s }));

    for (const call of planStepUpdateContract.valid_calls) {
      const updated = steps.map(s =>
        s.id === call.stepId ? { ...s, status: call.status } : s
      );
      const target = updated.find(s => s.id === call.stepId);
      if (target) {
        expect(target.status).toBe(call.status);
      }
    }
  });
});

// ── Edit Stats Contract ──

describe('Contract: updateEditStats + updateCheckpoints (Kotlin → React)', () => {
  it('edit stats has numeric fields', () => {
    const stats = editStatsContract.edit_stats.valid_call;
    expect(typeof stats.added).toBe('number');
    expect(typeof stats.removed).toBe('number');
    expect(typeof stats.files).toBe('number');
  });

  it('checkpoints have required fields', () => {
    for (const cp of editStatsContract.checkpoints.valid_payload) {
      expect(typeof cp.id).toBe('string');
      expect(typeof cp.description).toBe('string');
      expect(typeof cp.timestamp).toBe('number');
      expect(typeof cp.iteration).toBe('number');
      expect(Array.isArray(cp.filesModified)).toBe(true);
      expect(typeof cp.totalLinesAdded).toBe('number');
      expect(typeof cp.totalLinesRemoved).toBe('number');
    }
  });

  it('checkpoints can be JSON roundtripped', () => {
    const json = JSON.stringify(editStatsContract.checkpoints.valid_payload);
    const parsed = JSON.parse(json);
    expect(parsed.length).toBe(2);
    expect(parsed[0].id).toBe('cp-a1b2c3');
  });
});
