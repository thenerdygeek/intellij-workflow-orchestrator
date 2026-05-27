import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ChipPreview } from '../ChipPreview';
import type { PendingAttachment } from '../AttachmentManager';

const fileAtt: PendingAttachment = {
  sha256: 'bb', mime: 'application/pdf', size: 2048, originalFilename: 'spec.pdf',
  bytes: new Uint8Array(0), thumbnailUrl: '', kind: 'file', path: '/tmp/spec.pdf',
};

describe('ChipPreview file chip', () => {
  it('renders filename for a file attachment (no img thumbnail)', () => {
    render(<ChipPreview attachments={[fileAtt]} onRemove={() => {}} />);
    expect(screen.getByText('spec.pdf')).toBeInTheDocument();
    expect(screen.queryByRole('img')).toBeNull();
  });
});
