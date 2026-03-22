import { useEffect, useState, memo } from 'react';

let listeners: Array<(msg: string) => void> = [];

export function announce(message: string) {
  listeners.forEach(fn => fn(message));
}

export const ScreenReaderAnnouncer = memo(function ScreenReaderAnnouncer() {
  const [message, setMessage] = useState('');

  useEffect(() => {
    const handler = (msg: string) => {
      setMessage('');
      requestAnimationFrame(() => setMessage(msg));
    };
    listeners.push(handler);
    return () => { listeners = listeners.filter(fn => fn !== handler); };
  }, []);

  return (
    <div
      role="status"
      aria-live="polite"
      aria-atomic="true"
      style={{
        position: 'absolute',
        width: '1px',
        height: '1px',
        padding: 0,
        margin: '-1px',
        overflow: 'hidden',
        clip: 'rect(0,0,0,0)',
        whiteSpace: 'nowrap',
        border: 0,
      }}
    >
      {message}
    </div>
  );
});
