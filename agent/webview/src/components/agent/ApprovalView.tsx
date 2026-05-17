import { useId } from 'react';
import { ApprovalCard, type MetadataItem } from '@/components/ui/tool-ui/approval-card';
import { DiffHtml } from '@/components/rich/DiffHtml';
import { CommandPreview, type CommandPreviewProps } from '@/components/agent/CommandPreview';

interface ApprovalViewProps {
  toolName: string;
  riskLevel: string;
  title: string;
  description?: string;
  metadata?: MetadataItem[];
  diffContent?: string;
  commandPreview?: CommandPreviewProps;
  onApprove: () => void;
  onDeny: () => void;
  onAllowForSession?: () => void;
  originAgentId?: string | null;
  originLabel?: string | null;
}

export function ApprovalView({
  toolName,
  riskLevel,
  title,
  description,
  metadata,
  diffContent,
  commandPreview,
  onApprove,
  onDeny,
  onAllowForSession,
  originAgentId: _originAgentId,
  originLabel,
}: ApprovalViewProps) {
  const id = useId();

  const icon = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'shield-alert' : 'shield';
  const variant = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'destructive' as const : 'default' as const;

  const isRunCommand = toolName === 'run_command';

  return (
    <div className="flex flex-col gap-2">
      {originLabel && (
        <div className="text-[11px] mb-1" style={{ color: 'var(--accent)' }}>
          Sub-agent: {originLabel}
        </div>
      )}
      {isRunCommand && commandPreview && <CommandPreview {...commandPreview} />}
      {!isRunCommand && diffContent && (
        <div data-testid="approval-diff">
          <DiffHtml diffSource={diffContent} />
        </div>
      )}
      <ApprovalCard
        id={id}
        title={title}
        description={description}
        icon={icon}
        variant={variant}
        metadata={metadata}
        onConfirm={onApprove}
        onCancel={onDeny}
        onAllowForSession={onAllowForSession}
      />
    </div>
  );
}
