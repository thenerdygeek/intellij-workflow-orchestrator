import { useState, type MouseEvent, type ReactNode } from 'react';
import { LinkConfirmModal } from '@/components/markdown/LinkConfirmModal';

interface ChatLinkProps {
  href?: string;
  children?: ReactNode;
  className?: string;
  [key: string]: unknown;
}

export function ChatLink({ href, children, className, ...rest }: ChatLinkProps) {
  const [open, setOpen] = useState(false);

  const handleClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (!href) return;
    e.preventDefault();
    setOpen(true);
  };

  const linkClass =
    className ??
    'text-[var(--link)] underline decoration-[var(--link)]/30 hover:decoration-[var(--link)]';

  return (
    <>
      <a href={href} className={linkClass} onClick={handleClick} {...rest}>
        {children}
      </a>
      {open && href && (
        <LinkConfirmModal href={href} onClose={() => setOpen(false)} />
      )}
    </>
  );
}
