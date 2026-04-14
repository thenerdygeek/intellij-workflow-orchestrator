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
}: ApprovalViewProps) {
  const id = useId();

  const icon = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'shield-alert' : 'shield';
  const variant = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'destructive' as const : 'default' as const;

  const isRunCommand = toolName === 'run_command';

  return (
    <div className="flex flex-col gap-2">
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
