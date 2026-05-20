import { describe, it, expect, beforeEach } from 'vitest';
import { ShikiLruCache } from '../lib/shiki-cache';

describe('ShikiLruCache', () => {
  let cache: ShikiLruCache;
  beforeEach(() => { cache = new ShikiLruCache(3); });

  it('returns the cached value on hit', () => {
    cache.set('a', 'kotlin', true, '<pre>a</pre>');
    expect(cache.get('a', 'kotlin', true)).toBe('<pre>a</pre>');
  });

  it('treats different (code, language, isDark) tuples as different keys', () => {
    cache.set('a', 'kotlin', true, 'A-kt-dark');
    cache.set('a', 'kotlin', false, 'A-kt-light');
    cache.set('a', 'python', true, 'A-py-dark');
    expect(cache.get('a', 'kotlin', true)).toBe('A-kt-dark');
    expect(cache.get('a', 'kotlin', false)).toBe('A-kt-light');
    expect(cache.get('a', 'python', true)).toBe('A-py-dark');
  });

  it('evicts the least-recently-used entry on overflow', () => {
    cache.set('a', 'kotlin', true, 'A');
    cache.set('b', 'kotlin', true, 'B');
    cache.set('c', 'kotlin', true, 'C');
    cache.get('a', 'kotlin', true); // touch a — b becomes LRU
    cache.set('d', 'kotlin', true, 'D');
    expect(cache.get('b', 'kotlin', true)).toBeUndefined();
    expect(cache.get('a', 'kotlin', true)).toBe('A');
    expect(cache.get('c', 'kotlin', true)).toBe('C');
    expect(cache.get('d', 'kotlin', true)).toBe('D');
  });
});
