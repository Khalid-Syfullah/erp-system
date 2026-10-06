import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useRouterState } from '@tanstack/react-router';
import {
  Building,
  Check,
  ChevronDown,
  ChevronsUpDown,
  LogOut,
  Menu,
  Monitor,
  Moon,
  Search,
  Sun,
  UserCog,
} from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import { companiesOf, useMe } from '@/auth/session';
import { rememberCompany } from '@/auth/last-company';
import { permissionsOf } from '@/auth/permissions';
import { Button } from '@/components/ui/button';
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { t } from '@/i18n';
import { useTheme, type Theme } from '@/lib/theme';
import { cn } from '@/lib/utils';
import { globalAdminGroup, visibleGroups, type NavGroup } from './nav';

function useCompanyIdFromPath(): string | undefined {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const match = /^\/c\/([^/]+)/.exec(pathname);
  return match?.[1];
}

/** Whether the user is linked to an employee in the company (self-service menu). */
function useIsEmployee(companyId: string | undefined) {
  const { data } = useQuery({
    queryKey: ['c', companyId, 'me-employee'],
    queryFn: ({ signal }) =>
      api.get('/api/v1/companies/{companyId}/me/employee', { companyId: companyId! }, { signal }),
    enabled: !!companyId,
    staleTime: 5 * 60_000,
    retry: false,
  });
  return !!data;
}

function useNavigation() {
  const me = useMe();
  const companyId = useCompanyIdFromPath();
  const company = me.companies?.find((c) => c.id === companyId);
  const isEmployee = useIsEmployee(company ? companyId : undefined);
  return useMemo(() => {
    const groups: { group: NavGroup; base: string }[] = [];
    if (company) {
      const { canAny } = permissionsOf(company);
      for (const group of visibleGroups(canAny, isEmployee)) groups.push({ group, base: `/c/${company.id}` });
    }
    if (me.user?.isSystemAdmin) groups.push({ group: globalAdminGroup, base: '' });
    return { groups, company, companyId };
  }, [company, isEmployee, me.user?.isSystemAdmin, companyId]);
}

function isActive(pathname: string, href: string, exact: boolean): boolean {
  return exact ? pathname === href || pathname === `${href}/` : pathname === href || pathname.startsWith(`${href}/`);
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const { groups } = useNavigation();
  return (
    <nav aria-label={t('shell.mainNav')} className="flex-1 space-y-1 overflow-y-auto px-2 py-3 text-sm">
      {groups.map(({ group, base }) => {
        const Icon = group.icon;
        if (group.id === 'dashboard') {
          const href = base || '/';
          return (
            <Link
              key={group.id}
              to={href as '/'}
              onClick={onNavigate}
              aria-current={isActive(pathname, href, true) ? 'page' : undefined}
              className={cn(
                'flex items-center gap-2 rounded-md px-2 py-1.5 font-medium hover:bg-sidebar-accent',
                isActive(pathname, href, true) && 'bg-sidebar-accent text-sidebar-accent-foreground',
              )}
            >
              <Icon className="size-4" aria-hidden />
              {t(group.label)}
            </Link>
          );
        }
        const groupActive = group.items.some((item) => isActive(pathname, base + item.path, false));
        return (
          <Collapsible key={group.id} defaultOpen={groupActive}>
            <CollapsibleTrigger className="group flex w-full items-center gap-2 rounded-md px-2 py-1.5 font-medium hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:outline-none">
              <Icon className="size-4" aria-hidden />
              <span className="flex-1 text-left">{group.global ? `${t(group.label)} · ${t('shell.systemAdmin')}` : t(group.label)}</span>
              <ChevronDown className="size-4 transition-transform group-data-[state=open]:rotate-180" aria-hidden />
            </CollapsibleTrigger>
            <CollapsibleContent>
              <ul className="mt-0.5 mb-1 ml-4 space-y-0.5 border-l pl-2">
                {group.items.map((item) => {
                  const href = base + item.path;
                  const exact = item.path === '/reports';
                  const active = isActive(pathname, href, exact);
                  return (
                    <li key={item.path}>
                      <Link
                        to={href as '/'}
                        onClick={onNavigate}
                        aria-current={active ? 'page' : undefined}
                        className={cn(
                          'block rounded-md px-2 py-1 text-sidebar-foreground/85 hover:bg-sidebar-accent hover:text-sidebar-foreground',
                          active && 'bg-sidebar-accent font-medium text-sidebar-accent-foreground',
                        )}
                      >
                        {t(item.label)}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </CollapsibleContent>
          </Collapsible>
        );
      })}
    </nav>
  );
}

function CompanySwitcher() {
  const me = useMe();
  const navigate = useNavigate();
  const companyId = useCompanyIdFromPath();
  const companies = companiesOf(me);
  const current = companies.find((c) => c.id === companyId);
  if (companies.length === 0) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" className="max-w-64 justify-between gap-2" aria-label={t('shell.switchCompany')}>
          <Building className="size-4" aria-hidden />
          <span className="truncate">{current?.displayName ?? t('shell.switchCompany')}</span>
          <ChevronsUpDown className="size-4 opacity-60" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel>{t('shell.switchCompany')}</DropdownMenuLabel>
        {companies.map((company) => (
          <DropdownMenuItem
            key={company.id}
            onSelect={() => {
              rememberCompany(company.id!);
              void navigate({ to: '/c/$companyId', params: { companyId: company.id! } });
            }}
          >
            <Check className={cn('size-4', company.id === companyId ? 'opacity-100' : 'opacity-0')} aria-hidden />
            <div className="min-w-0">
              <div className="truncate">{company.displayName}</div>
              <div className="text-xs text-muted-foreground">{company.code}</div>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function UserMenu() {
  const me = useMe();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const signOut = async () => {
    try {
      await api.post('/api/v1/auth/logout', {}, { anonymous: true });
    } finally {
      queryClient.clear();
      void navigate({ to: '/login' });
    }
  };
  const initials = (me.user?.displayName ?? me.user?.email ?? '?')
    .split(/\s+/)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2" aria-label={t('shell.account')}>
          <span className="flex size-7 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground" aria-hidden>
            {initials}
          </span>
          <span className="hidden max-w-40 truncate md:inline">{me.user?.displayName}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel>
          <div className="truncate">{me.user?.displayName}</div>
          <div className="truncate text-xs font-normal text-muted-foreground">{me.user?.email}</div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void navigate({ to: '/account' })}>
          <UserCog aria-hidden />
          {t('shell.account')}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">{t('shell.theme')}</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={theme} onValueChange={(v) => setTheme(v as Theme)}>
          <DropdownMenuRadioItem value="light">
            <Sun aria-hidden /> {t('shell.themeLight')}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="dark">
            <Moon aria-hidden /> {t('shell.themeDark')}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="system">
            <Monitor aria-hidden /> {t('shell.themeSystem')}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void signOut()}>
          <LogOut aria-hidden />
          {t('auth.signOut')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Ctrl/⌘+K: jump to any page the user can open. */
function CommandMenu() {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const { groups } = useNavigation();
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((o) => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <>
      <Button variant="outline" className="hidden w-56 justify-start gap-2 text-muted-foreground sm:flex" onClick={() => setOpen(true)}>
        <Search className="size-4" aria-hidden />
        <span className="flex-1 text-left">{t('shell.commandHint')}</span>
        <kbd className="rounded border px-1 text-[10px]">Ctrl K</kbd>
      </Button>
      <Button variant="ghost" size="icon" className="sm:hidden" onClick={() => setOpen(true)} aria-label={t('shell.commandHint')}>
        <Search aria-hidden />
      </Button>
      <CommandDialog open={open} onOpenChange={setOpen} title={t('shell.commandHint')} description={t('shell.commandPlaceholder')}>
        <CommandInput placeholder={t('shell.commandPlaceholder')} />
        <CommandList>
          <CommandEmpty>{t('common.noOptions')}</CommandEmpty>
          {groups.map(({ group, base }) => (
            <CommandGroup key={group.id} heading={t(group.label)}>
              {group.items.map((item) => (
                <CommandItem
                  key={item.path}
                  value={`${t(group.label)} ${t(item.label)} ${item.keywords ?? ''}`}
                  onSelect={() => {
                    setOpen(false);
                    void navigate({ to: (base + item.path || '/') as '/' });
                  }}
                >
                  {t(item.label)}
                </CommandItem>
              ))}
            </CommandGroup>
          ))}
        </CommandList>
      </CommandDialog>
    </>
  );
}

/** The signed-in layout: navigation, company switcher, user menu and the page. */
export function AppShell({ children }: { children: ReactNode }) {
  const [mobileOpen, setMobileOpen] = useState(false);

  const brand = (
    <Link to="/" className="flex h-14 items-center gap-2 border-b px-4 font-semibold">
      <span className="flex size-7 items-center justify-center rounded-md bg-primary text-xs text-primary-foreground" aria-hidden>
        E
      </span>
      {t('app.name')}
    </Link>
  );

  return (
    <div className="flex min-h-svh bg-background">
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-primary px-3 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        {t('app.skipToContent')}
      </a>
      <aside className="sticky top-0 hidden h-svh w-64 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground lg:flex">
        {brand}
        <Sidebar />
      </aside>
      <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
        <SheetContent side="left" className="w-72 bg-sidebar p-0 text-sidebar-foreground">
          <SheetTitle className="sr-only">{t('shell.mainNav')}</SheetTitle>
          <SheetDescription className="sr-only">{t('shell.mainNav')}</SheetDescription>
          <div className="flex h-full flex-col">
            {brand}
            <Sidebar onNavigate={() => setMobileOpen(false)} />
          </div>
        </SheetContent>
      </Sheet>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b bg-background/95 px-3 backdrop-blur sm:px-4">
          <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setMobileOpen(true)} aria-label={t('common.toggleSidebar')}>
            <Menu aria-hidden />
          </Button>
          <CompanySwitcher />
          <div className="ml-auto flex items-center gap-2">
            <CommandMenu />
            <UserMenu />
          </div>
        </header>
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 p-3 outline-none sm:p-6">
          {children}
        </main>
      </div>
    </div>
  );
}
