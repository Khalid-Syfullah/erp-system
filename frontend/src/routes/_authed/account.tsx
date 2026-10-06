import { createFileRoute } from '@tanstack/react-router';
import { AccountPage } from '@/modules/account/account-page';

export const Route = createFileRoute('/_authed/account')({ component: AccountPage });
