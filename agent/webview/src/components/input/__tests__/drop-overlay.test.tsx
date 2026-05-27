import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { DropOverlay } from '../DropOverlay';

describe('DropOverlay', () => {
  it('renders prompt when active', () => {
    render(<DropOverlay active={true} />);
    expect(screen.getByText(/drop files to attach/i)).toBeInTheDocument();
  });
  it('renders nothing when inactive', () => {
    const { container } = render(<DropOverlay active={false} />);
    expect(container.firstChild).toBeNull();
  });
});
