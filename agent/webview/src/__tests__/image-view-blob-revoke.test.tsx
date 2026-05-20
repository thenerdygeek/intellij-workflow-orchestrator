import { describe, it, expect, vi } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { ImageView } from '../components/rich/ImageView';

describe('ImageView blob URL lifecycle', () => {
  it('revokes a blob URL on unmount', () => {
    const revoke = vi.spyOn(URL, 'revokeObjectURL');
    const blobUrl = 'blob:http://workflow-agent/abcdef';
    const imageSource = JSON.stringify({ src: blobUrl, alt: 'x' });
    const { unmount } = render(<ImageView imageSource={imageSource} />);
    unmount();
    expect(revoke).toHaveBeenCalledWith(blobUrl);
    cleanup();
  });

  it('does not revoke non-blob URLs', () => {
    const revoke = vi.spyOn(URL, 'revokeObjectURL');
    revoke.mockClear();
    const imageSource = JSON.stringify({ src: 'http://workflow-agent/static/foo.png', alt: 'x' });
    const { unmount } = render(<ImageView imageSource={imageSource} />);
    unmount();
    expect(revoke).not.toHaveBeenCalled();
    cleanup();
  });
});
