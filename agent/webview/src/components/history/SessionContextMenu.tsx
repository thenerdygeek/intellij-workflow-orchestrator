import { MessageSquare, Star, Download, Trash2 } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { HistoryItem } from '../../bridge/types';

interface SessionContextMenuProps {
  x: number;
  y: number;
  item: HistoryItem;
  onResume: (id: string) => void;
  onToggleFavorite: (id: string) => void;
  onExport: (id: string) => void;
  onDelete: (id: string) => void;
  onClose: () => void;
}

export function SessionContextMenu({
  x, y, item, onResume, onToggleFavorite, onExport, onDelete, onClose,
}: SessionContextMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [onClose]);

  // Adjust position to keep menu within viewport
  const menuStyle: React.CSSProperties = {
    position: 'fixed',
    left: x,
    top: y,
    zIndex: 50,
  };

  const items = [
    {
      label: 'Resume',
      icon: <MessageSquare size={13} />,
      onClick: () => { onResume(item.id); onClose(); },
      className: 'text-[var(--fg)]',
    },
    {
      label: item.isFavorited ? 'Unfavorite' : 'Favorite',
      icon: <Star size={13} className={item.isFavorited ? 'fill-[var(--warning)] text-[var(--warning)]' : ''} />,
      onClick: () => { onToggleFavorite(item.id); onClose(); },
      className: 'text-[var(--fg)]',
    },
    {
      label: 'Export',
      icon: <Download size={13} />,
      onClick: () => { onExport(item.id); onClose(); },
      className: 'text-[var(--fg)]',
    },
    {
      label: 'Delete',
      icon: <Trash2 size={13} />,
      onClick: () => { onDelete(item.id); onClose(); },
      className: 'text-[var(--error)]',
    },
  ];

  return (
    <div ref={menuRef} style={menuStyle}>
      <div
        className="min-w-[160px] rounded-md border border-[var(--border)] bg-[var(--toolbar-bg)]
          shadow-lg overflow-hidden py-1"
      >
        {items.map((menuItem) => (
          <button
            key={menuItem.label}
            onClick={menuItem.onClick}
            className={`flex items-center gap-2 w-full px-3 py-1.5 text-[12px]
              hover:bg-[var(--hover-overlay-strong)] transition-colors duration-150
              ${menuItem.className}`}
          >
            {menuItem.icon}
            {menuItem.label}
          </button>
        ))}
      </div>
    </div>
  );
}
