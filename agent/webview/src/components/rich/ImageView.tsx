import { useState } from 'react';
import { RichBlock } from './RichBlock';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog';
import { ImageIcon } from 'lucide-react';

interface ImageData {
  src: string;
  alt?: string;
  caption?: string;
}

interface ImageViewProps {
  imageSource: string;
}

export function ImageView({ imageSource }: ImageViewProps) {
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(false);

  let data: ImageData;
  try {
    data = JSON.parse(imageSource);
  } catch {
    return (
      <RichBlock type="image" source={imageSource} error={new Error('Invalid image JSON')}>
        <div />
      </RichBlock>
    );
  }

  if (error) {
    return (
      <RichBlock type="image" source={imageSource}>
        <div className="flex items-center justify-center gap-2 p-8" style={{ color: 'var(--fg-muted)' }}>
          <ImageIcon className="h-5 w-5" />
          <span className="text-[12px]">Failed to load image</span>
        </div>
      </RichBlock>
    );
  }

  return (
    <RichBlock type="image" source={imageSource}>
      <div>
        <Dialog>
          <DialogTrigger asChild>
            <button className="w-full cursor-zoom-in border-0 bg-transparent p-0">
              {!loaded && (
                <div
                  className="flex items-center justify-center h-48 animate-pulse"
                  style={{ backgroundColor: 'var(--tool-bg)' }}
                >
                  <ImageIcon className="h-8 w-8" style={{ color: 'var(--fg-muted)' }} />
                </div>
              )}
              <img
                src={data.src}
                alt={data.alt ?? ''}
                onLoad={() => setLoaded(true)}
                onError={() => setError(true)}
                className={`max-w-full h-auto ${loaded ? '' : 'hidden'}`}
                style={{ maxHeight: '400px', objectFit: 'contain' }}
              />
            </button>
          </DialogTrigger>
          <DialogContent
            className="max-w-5xl max-h-[90vh] p-2"
            style={{ backgroundColor: 'var(--bg)' }}
          >
            <img
              src={data.src}
              alt={data.alt ?? ''}
              className="w-full h-full object-contain"
              style={{ maxHeight: '85vh' }}
            />
          </DialogContent>
        </Dialog>
        {data.caption && (
          <div
            className="px-3 py-2 text-[11px] text-center"
            style={{ color: 'var(--fg-muted)' }}
          >
            {data.caption}
          </div>
        )}
      </div>
    </RichBlock>
  );
}
