import { describe, it, expect } from 'vitest';
import { describeMemoryOp } from '@/lib/describeMemoryOp';
import { BrainCircuit, BookOpen, Pencil, Trash2, ListTree } from 'lucide-react';

const POSIX_MEM = '/Users/me/.workflow-orchestrator/MyPlugin-a3f8b2/agent/memory/feedback_dont_cite_x.md';
const WIN_MEM = 'C:\\Users\\me\\.workflow-orchestrator\\MyPlugin-a3f8b2\\agent\\memory\\feedback_dont_cite_x.md';
const POSIX_INDEX = '/Users/me/.workflow-orchestrator/MyPlugin-a3f8b2/agent/memory/MEMORY.md';
const NON_MEM = '/Users/me/projects/MyPlugin/src/main/Foo.kt';

describe('describeMemoryOp', () => {
  it('returns null for an undefined path', () => {
    expect(describeMemoryOp('edit_file', undefined)).toBeNull();
  });

  it('returns null for a non-memory path', () => {
    expect(describeMemoryOp('edit_file', NON_MEM)).toBeNull();
  });

  it('labels create_file as "Creating memory" with BrainCircuit icon and type chip', () => {
    const result = describeMemoryOp('create_file', POSIX_MEM);
    expect(result).not.toBeNull();
    expect(result!.icon).toBe(BrainCircuit);
    expect(result!.title).toBe('Creating memory · dont_cite_x (feedback)');
  });

  it('labels read_file as "Reading memory" with BookOpen icon', () => {
    const result = describeMemoryOp('read_file', POSIX_MEM);
    expect(result!.icon).toBe(BookOpen);
    expect(result!.title).toBe('Reading memory · dont_cite_x');
  });

  it('labels edit_file (non-index) as "Updating memory" with Pencil icon', () => {
    const result = describeMemoryOp('edit_file', POSIX_MEM);
    expect(result!.icon).toBe(Pencil);
    expect(result!.title).toBe('Updating memory · dont_cite_x');
  });

  it('labels delete_file as "Deleting memory" with Trash2 icon', () => {
    const result = describeMemoryOp('delete_file', POSIX_MEM);
    expect(result!.icon).toBe(Trash2);
    expect(result!.title).toBe('Deleting memory · dont_cite_x');
  });

  it('labels edit_file MEMORY.md as "Curating memory index" with ListTree icon', () => {
    const result = describeMemoryOp('edit_file', POSIX_INDEX);
    expect(result!.icon).toBe(ListTree);
    expect(result!.title).toBe('Curating memory index');
  });

  it('labels read_file MEMORY.md as "Reading memory index"', () => {
    const result = describeMemoryOp('read_file', POSIX_INDEX);
    expect(result!.icon).toBe(ListTree);
    expect(result!.title).toBe('Reading memory index');
  });

  it('detects Windows-style paths with backslash separators', () => {
    const result = describeMemoryOp('create_file', WIN_MEM);
    expect(result).not.toBeNull();
    expect(result!.title).toBe('Creating memory · dont_cite_x (feedback)');
  });

  it('labels create_file MEMORY.md as "Curating memory index"', () => {
    const result = describeMemoryOp('create_file', POSIX_INDEX);
    expect(result!.icon).toBe(ListTree);
    expect(result!.title).toBe('Curating memory index');
  });

  it('omits the type chip when filename has no underscore', () => {
    const result = describeMemoryOp('create_file',
      '/Users/me/.workflow-orchestrator/MyPlugin-a3f8b2/agent/memory/standalone.md');
    expect(result!.title).toBe('Creating memory · standalone');
  });

  it('returns null for an unknown tool name on a memory path', () => {
    expect(describeMemoryOp('grep', POSIX_MEM)).toBeNull();
  });
});
