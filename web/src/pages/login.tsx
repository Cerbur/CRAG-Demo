import { type JSX } from 'react';
import { useNavigate, useLocation } from 'react-router';
import { LoginForm } from '@features/auth/components/login-form';
import { useLoginViewModel } from '@app/session/use-auth-view-model';
import { ROUTE_PATHS } from '@app/routes';

interface LocationState {
  readonly from?: string;
}

/**
 * Login page. Wires the LoginForm to the login ViewModel. On success navigates
 * to the originally requested page (if any) or the default Knowledge route.
 */
export function LoginPage(): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as LocationState | null)?.from;

  const viewModel = useLoginViewModel({
    onAuthenticated: () => {
      navigate(from ?? ROUTE_PATHS.knowledgeList, { replace: true });
    },
  });

  return <LoginForm viewModel={viewModel} />;
}
