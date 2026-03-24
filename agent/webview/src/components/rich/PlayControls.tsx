import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Play, Pause, SkipBack, SkipForward, RotateCcw } from 'lucide-react';

interface PlayControlsProps {
  totalSteps: number;
  currentStep: number;
  onStepChange: (step: number) => void;
  autoPlayInterval?: number;
  className?: string;
}

export function PlayControls({
  totalSteps,
  currentStep,
  onStepChange,
  autoPlayInterval = 1200,
  className,
}: PlayControlsProps) {
  const [playing, setPlaying] = useState(false);

  const handlePlay = () => setPlaying(true);
  const handlePause = () => setPlaying(false);
  const handleStepBack = () => { setPlaying(false); onStepChange(Math.max(0, currentStep - 1)); };
  const handleStepForward = () => { setPlaying(false); onStepChange(Math.min(totalSteps - 1, currentStep + 1)); };
  const handleReset = () => { setPlaying(false); onStepChange(0); };

  // Auto-advance: setTimeout triggered by currentStep changes when playing
  useEffect(() => {
    if (!playing) return;
    const timer = setTimeout(() => {
      if (currentStep < totalSteps - 1) {
        onStepChange(currentStep + 1);
      } else {
        setPlaying(false);
      }
    }, autoPlayInterval);
    return () => clearTimeout(timer);
  }, [playing, currentStep, totalSteps, autoPlayInterval, onStepChange]);

  return (
    <div
      className={`flex items-center justify-center gap-1 py-2 ${className ?? ''}`}
      style={{ borderTop: '1px solid var(--border)' }}
    >
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleReset} title="Reset">
        <RotateCcw className="h-3 w-3" />
      </Button>
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleStepBack} title="Previous step" disabled={currentStep <= 0}>
        <SkipBack className="h-3 w-3" />
      </Button>
      {playing ? (
        <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handlePause} title="Pause">
          <Pause className="h-3.5 w-3.5" />
        </Button>
      ) : (
        <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handlePlay} title="Play" disabled={currentStep >= totalSteps - 1}>
          <Play className="h-3.5 w-3.5" />
        </Button>
      )}
      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={handleStepForward} title="Next step" disabled={currentStep >= totalSteps - 1}>
        <SkipForward className="h-3 w-3" />
      </Button>
      <span className="ml-2 text-[10px] font-mono tabular-nums" style={{ color: 'var(--fg-muted)' }}>
        {currentStep + 1} / {totalSteps}
      </span>
    </div>
  );
}
