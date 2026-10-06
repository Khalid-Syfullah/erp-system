import type { ReactNode } from 'react';

/** The centered card of the sign-in pages. */
export function AuthLayout({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <main id="main" className="flex min-h-svh items-center justify-center bg-muted/40 p-4">
      <div className="w-full max-w-sm space-y-6 rounded-xl border bg-card p-6 shadow-sm">
        <div className="space-y-1.5 text-center">
          <div className="mx-auto flex size-10 items-center justify-center rounded-lg bg-primary font-semibold text-primary-foreground" aria-hidden>
            E
          </div>
          <h1 className="text-xl font-semibold">{title}</h1>
          {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
        </div>
        {children}
      </div>
    </main>
  );
}
