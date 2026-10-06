import { toast } from 'sonner';
import { isApiError } from '@/api/errors';
import { t } from '@/i18n';
import { problemMessage } from './problem';

/** Toast notifications. Errors show the problem message and the request ID for support. */
export const notify = {
  success(message: string) {
    toast.success(message);
  },
  info(message: string) {
    toast.info(message);
  },
  error(error: unknown) {
    const requestId = isApiError(error) ? error.problem.requestId : undefined;
    toast.error(problemMessage(error), {
      description: requestId ? t('states.requestId', { id: requestId }) : undefined,
    });
  },
};
