import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { AppProviders } from '../src/app/providers';

describe('AppProviders', () => {
  it('renders children inside Ant Design ConfigProvider, QueryClient, Router and ErrorBoundary', () => {
    const { getByText } = render(
      <AppProviders>
        <div>child-marker</div>
      </AppProviders>,
    );
    expect(getByText('child-marker')).toBeTruthy();
  });
});
