import { useId } from 'react';
import { ApprovalCard, type MetadataItem } from '@/components/ui/tool-ui/approval-card';

interface ApprovalViewProps {
  title: string;
  description?: string;
  commandPreview?: string;
  onApprove: () => void;
  onDeny: () => void;
}

export function ApprovalView({ title, description, commandPreview, onApprove, onDeny }: ApprovalViewProps) {
  const id = useId();

  const metadata: MetadataItem[] | undefined = commandPreview
    ? [{ key: 'Command', value: commandPreview }]
    : undefined;

  return (
    <ApprovalCard
      id={id}
      title={title}
      description={description}
      icon="shield-alert"
      variant="destructive"
      metadata={metadata}
      onConfirm={onApprove}
      onCancel={onDeny}
    />
  );
}
