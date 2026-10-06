import { Toaster as Sonner, type ToasterProps } from 'sonner';

import { useTheme } from '@/lib/theme';

/** Notification toasts, themed like the rest of the app (light, dark or the system's). */
const Toaster = ({ ...props }: ToasterProps) => {
  const { resolved } = useTheme();
  return (
    <Sonner
      theme={resolved}
      className="toaster group"
      style={
        {
          '--normal-bg': 'var(--popover)',
          '--normal-text': 'var(--popover-foreground)',
          '--normal-border': 'var(--border)',
        } as React.CSSProperties
      }
      {...props}
    />
  );
};

export { Toaster };
