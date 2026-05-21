import { BrainCircuit, BookOpen, Pencil, Trash2, ListTree, type LucideIcon } from 'lucide-react';

export interface MemoryOpLabel {
  icon: LucideIcon;
  title: string;
}

// Matches both POSIX and Windows separators. The {6-char-sha} project suffix is
// part of any path under ~/.workflow-orchestrator/, so we don't constrain it.
const MEMORY_PATH_RE = /[\\/]\.workflow-orchestrator[\\/].+?[\\/]agent[\\/]memory[\\/]([^\\/]+\.md)$/;

export function describeMemoryOp(toolName: string, path: string | undefined): MemoryOpLabel | null {
  if (!path) return null;
  const match = path.match(MEMORY_PATH_RE);
  if (!match || !match[1]) return null;
  const filename = match[1];

  if (filename === 'MEMORY.md') {
    if (toolName === 'edit_file' || toolName === 'create_file') {
      return { icon: ListTree, title: 'Curating memory index' };
    }
    if (toolName === 'read_file') {
      return { icon: ListTree, title: 'Reading memory index' };
    }
    return null;
  }

  const slug = filename.replace(/\.md$/, '');
  const underscoreIdx = slug.indexOf('_');
  const type = underscoreIdx !== -1 ? slug.slice(0, underscoreIdx) : null;
  const displayName = underscoreIdx !== -1 ? slug.slice(underscoreIdx + 1) : slug;

  switch (toolName) {
    case 'create_file':
      return { icon: BrainCircuit, title: `Creating memory · ${displayName}${type ? ` (${type})` : ''}` };
    case 'read_file':
      return { icon: BookOpen, title: `Reading memory · ${displayName}` };
    case 'edit_file':
      return { icon: Pencil, title: `Updating memory · ${displayName}` };
    case 'delete_file':
      return { icon: Trash2, title: `Deleting memory · ${displayName}` };
    default:
      return null;
  }
}
