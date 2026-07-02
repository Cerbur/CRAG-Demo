import { type JSX } from 'react';
import { useNavigate } from 'react-router';
import { RegisterForm } from '@features/auth/components/register-form';
import { useRegisterViewModel } from '@app/session/use-auth-view-model';
import { ROUTE_PATHS } from '@app/routes';

/**
 * Register page. Wires the RegisterForm to the register ViewModel. On success
 * navigates to the default Knowledge route.
 */
export function RegisterPage(): JSX.Element {
  const navigate = useNavigate();

  const viewModel = useRegisterViewModel({
    onAuthenticated: () => {
      navigate(ROUTE_PATHS.knowledgeList, { replace: true });
    },
  });

  return <RegisterForm viewModel={viewModel} />;
}
