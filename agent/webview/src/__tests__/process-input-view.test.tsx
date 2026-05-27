/**
 * Interaction coverage for ProcessInputView — the inline prompt that feeds stdin
 * to a running process (ask_user_input / send_stdin). Locks the submit contract:
 * a trailing newline is appended (once), empty input can't submit, Enter submits
 * while Shift+Enter does not, and a long target command is truncated in the UI.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ProcessInputView } from '@/components/agent/ProcessInputView';

afterEach(() => { document.body.innerHTML = ''; });

function setup(onSubmit = vi.fn(), command = 'npm run dev') {
  render(
    <ProcessInputView
      processId="p1"
      description="The process is waiting for input"
      prompt="Continue? (y/n)"
      command={command}
      onSubmit={onSubmit}
    />
  );
  return { onSubmit, input: screen.getByPlaceholderText(/enter input/i), send: screen.getByRole('button', { name: /send/i }) };
}

describe('ProcessInputView', () => {
  it('appends a trailing newline on submit', () => {
    const { onSubmit, input, send } = setup();
    fireEvent.change(input, { target: { value: 'yes' } });
    fireEvent.click(send);
    expect(onSubmit).toHaveBeenCalledWith('yes\n');
  });

  it('does not double the newline if the input already ends with one', () => {
    const { onSubmit, input, send } = setup();
    fireEvent.change(input, { target: { value: 'yes\n' } });
    fireEvent.click(send);
    expect(onSubmit).toHaveBeenCalledWith('yes\n');
  });

  it('cannot submit empty input', () => {
    const { onSubmit, send } = setup();
    expect(send).toBeDisabled();
    fireEvent.click(send);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('Enter submits; Shift+Enter does not', () => {
    const { onSubmit, input } = setup();
    fireEvent.change(input, { target: { value: 'go' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledWith('go\n');
  });

  it('truncates a long target command in the display', () => {
    const long = 'x'.repeat(80);
    setup(vi.fn(), long);
    expect(screen.getByText(/x{47}\.\.\./)).toBeInTheDocument();
    expect(screen.queryByText(long)).toBeNull();
  });
});
