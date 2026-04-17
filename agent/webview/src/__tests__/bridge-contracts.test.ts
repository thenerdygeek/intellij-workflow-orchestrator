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
import planReviseV2Contract from './contracts/plan-revise-v2.json';
import planDataContract from './contracts/plan-data.json';
import editStatsContract from './contracts/edit-stats.json';
import taskCreateContract from './contracts/task-create.json';
import taskUpdateContract from './contracts/task-update.json';
import taskListContract from './contracts/task-list.json';

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

  it('plan can be JSON.stringify + JSON.parse roundtripped', () => {
    const json = JSON.stringify(planDataContract.payload);
    const parsed = JSON.parse(json);
    expect(parsed.goal).toBe(planDataContract.payload.goal);
  });

  it('optional fields are present in payload', () => {
    const plan = planDataContract.payload;
    for (const field of planDataContract.optional_fields) {
      expect(plan).toHaveProperty(field);
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

// ── Plan Revise V2 Contract (per-line comments) ──

describe('Contract: _revisePlan v2 payload with per-line comments (React -> Kotlin)', () => {
  it('valid payloads are parseable JSON with comments array and markdown string', () => {
    for (const testCase of planReviseV2Contract.valid_payloads) {
      const parsed = JSON.parse(testCase.payload);
      expect(typeof parsed).toBe('object');
      expect(Array.isArray(parsed.comments)).toBe(true);
      expect(typeof parsed.markdown).toBe('string');
      expect(parsed.comments.length).toBe(testCase.expected_comment_count);
    }
  });

  it('each comment has line (number), content (string), and comment (string)', () => {
    for (const testCase of planReviseV2Contract.valid_payloads) {
      const parsed = JSON.parse(testCase.payload);
      for (const comment of parsed.comments) {
        expect(typeof comment.line).toBe('number');
        expect(typeof comment.content).toBe('string');
        expect(typeof comment.comment).toBe('string');
      }
    }
  });

  it('simulated PlanEditor revise produces valid v2 payload', () => {
    // Simulate what the rewritten plan-editor.tsx handleRevise does
    const comments = [
      { lineNumber: 10, text: 'Handle empty string', lineContent: 'val customer = order.customer' },
      { lineNumber: 28, text: 'Add integration tests', lineContent: '### 3. Run tests' },
    ];
    const markdown = '## Goal\nFix NPE in PaymentService';

    const payload = JSON.stringify({
      comments: comments.map(c => ({
        line: c.lineNumber,
        content: c.lineContent,
        comment: c.text,
      })),
      markdown,
    });

    const parsed = JSON.parse(payload);
    expect(Array.isArray(parsed.comments)).toBe(true);
    expect(parsed.comments.length).toBe(2);
    expect(parsed.comments[0].line).toBe(10);
    expect(parsed.comments[0].content).toBe('val customer = order.customer');
    expect(parsed.comments[0].comment).toBe('Handle empty string');
    expect(typeof parsed.markdown).toBe('string');
    expect(parsed.markdown).toContain('## Goal');
  });

  it('empty comments array is valid', () => {
    const parsed = JSON.parse(planReviseV2Contract.valid_payloads[1].payload);
    expect(parsed.comments).toEqual([]);
    expect(parsed.markdown).toBeTruthy();
  });
});

// ── Task Create Contract ──

describe('Contract: applyTaskCreate (Kotlin → React)', () => {
  it('required fields present', () => {
    for (const field of taskCreateContract.required_fields) {
      expect(Object.keys(taskCreateContract.payload)).toContain(field);
    }
  });
  it('status is a valid enum value', () => {
    expect(taskCreateContract.valid_statuses).toContain(taskCreateContract.payload.status);
  });
});

// ── Task Update Contract ──

describe('Contract: applyTaskUpdate (Kotlin → React)', () => {
  it('required fields present', () => {
    for (const field of taskUpdateContract.required_fields) {
      expect(Object.keys(taskUpdateContract.payload)).toContain(field);
    }
  });
  it('status is a valid enum value', () => {
    expect(taskUpdateContract.valid_statuses).toContain(taskUpdateContract.payload.status);
  });
});

// ── Task List Contract ──

describe('Contract: setTasks (Kotlin → React)', () => {
  it('payload is an array', () => {
    expect(Array.isArray(taskListContract.payload)).toBe(true);
  });
  it('every task has required fields', () => {
    for (const task of taskListContract.payload) {
      for (const field of taskListContract.required_fields_per_task) {
        expect(Object.keys(task as object)).toContain(field);
      }
    }
  });
});
