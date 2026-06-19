import { describe, it, expect } from 'vitest';
import { coerceSnapshotArray } from '../bridge/jcef-bridge';

describe('coerceSnapshotArray', () => {
  it('parses a JSON-array string into an array', () => {
    const input = '[{"id":"bg-1","status":"running"},{"id":"bg-2","status":"done"}]';
    const result = coerceSnapshotArray(input);
    expect(result).toEqual([{ id: 'bg-1', status: 'running' }, { id: 'bg-2', status: 'done' }]);
  });

  it('returns an already-parsed array as-is', () => {
    const input = [{ id: 'mon-1' }, { id: 'mon-2' }];
    const result = coerceSnapshotArray(input);
    expect(result).toBe(input);
  });

  it('returns [] for a non-array JSON string (JSON object)', () => {
    const result = coerceSnapshotArray('{"key":"value"}');
    expect(result).toEqual([]);
  });

  it('returns [] for malformed JSON', () => {
    const result = coerceSnapshotArray('not-valid-json{{');
    expect(result).toEqual([]);
  });

  it('returns [] for null', () => {
    expect(coerceSnapshotArray(null)).toEqual([]);
  });

  it('returns [] for undefined', () => {
    expect(coerceSnapshotArray(undefined)).toEqual([]);
  });

  it('returns [] for an empty string', () => {
    expect(coerceSnapshotArray('')).toEqual([]);
  });

  it('returns [] for a numeric value', () => {
    expect(coerceSnapshotArray(42)).toEqual([]);
  });

  it('parses an empty JSON array string into an empty array', () => {
    expect(coerceSnapshotArray('[]')).toEqual([]);
  });
});
