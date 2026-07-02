import type { JSX } from 'react';
import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

interface PlaceholderProps {
  title: string;
  description?: string;
}

/**
 * Stable placeholder heading for 22.1 routes. No business content, no API calls.
 * Each feature task (22.3+) replaces its placeholder with the real page.
 */
export function PagePlaceholder({ title, description }: PlaceholderProps): JSX.Element {
  return (
    <div role="region" aria-label={title}>
      <Title level={2} style={{ marginTop: 0 }}>
        {title}
      </Title>
      {description ? <Paragraph type="secondary">{description}</Paragraph> : null}
    </div>
  );
}
