/** "Última visita: hoje / ontem / há 12 dias / em 03/10/2026" — relative to [now] (device clock). */
export function lastVisitLabel(lastVisit: string | null, now: Date = new Date()): string {
  if (!lastVisit) return 'Ainda sem visitas';
  const visit = new Date(lastVisit);
  const days = Math.floor((startOfDay(now) - startOfDay(visit)) / 86_400_000);
  if (days <= 0) return 'Última visita hoje';
  if (days === 1) return 'Última visita ontem';
  if (days < 60) return `Última visita há ${days} dias`;
  return `Última visita em ${visit.toLocaleDateString('pt-BR')}`;
}

/** "Costuma voltar a cada 32 dias" (shown only with 3+ visits; spec "Customer history"). */
export function usualIntervalLabel(days: number | null): string | null {
  if (days === null) return null;
  return days === 1 ? 'Costuma voltar todo dia' : `Costuma voltar a cada ${days} dias`;
}

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}
