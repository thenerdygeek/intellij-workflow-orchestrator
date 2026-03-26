import { useId } from 'react';
import { ApprovalCard, type MetadataItem } from '@/components/ui/tool-ui/approval-card';
import { DiffHtml } from '@/components/rich/DiffHtml';

interface ApprovalViewProps {
  toolName: string;
  riskLevel: string;
  title: string;
  description?: string;
  metadata?: MetadataItem[];
  diffContent?: string;
  onApprove: () => void;
  onDeny: () => void;
  onAllowForSession: () => void;
}

export function ApprovalView({ toolName: _toolName, riskLevel, title, description, metadata, diffContent, onApprove, onDeny, onAllowForSession }: ApprovalViewProps) {
  const id = useId();

  const icon = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'shield-alert' : 'shield';
  const variant = riskLevel === 'DESTRUCTIVE' || riskLevel === 'HIGH' ? 'destructive' as const : 'default' as const;

  return (
    <div className="flex flex-col gap-2">
      {diffContent && (
        <DiffHtml diffSource={diffContent} />
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
