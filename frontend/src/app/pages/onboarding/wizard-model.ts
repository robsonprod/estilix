import { EstablishmentType, ServiceInput } from '../../core/models';

/** A suggested service in the wizard: the owner may uncheck it or change price and duration. */
export interface ServiceChoice extends ServiceInput {
  selected: boolean;
}

/** "45", "45,5" or "45,50" → "45.50"; anything else is returned as typed so the API names the error. */
export function toDecimal(text: string): string {
  const t = text.trim().replace(/\s/g, '').replace(/^R\$/, '');
  if (/^\d+([.,]\d{1,2})?$/.test(t)) {
    const [int, dec = ''] = t.replace(',', '.').split('.');
    return `${int}.${dec.padEnd(2, '0')}`;
  }
  return t;
}

/** "45.00" → "45,00" for display in inputs. */
export function toBrazilian(decimal: string): string {
  return decimal.replace('.', ',');
}

/** Which wizard step owns an API field error, so the wizard can jump back to it. */
export function stepOf(field: string): 1 | 2 | 3 {
  if (field === 'establishmentType' || field === 'planCode') return 1;
  if (field.startsWith('services') || field === 'acceptTerms') return 3;
  return 2;
}

export function choicesFrom(presets: ServiceInput[]): ServiceChoice[] {
  return presets.map((p) => ({ ...p, selected: true }));
}

export const TYPE_OPTIONS: { value: EstablishmentType; title: string; text: string }[] = [
  { value: 'SALON', title: 'Salão de beleza', text: 'Cabelo, unhas, sobrancelha e tratamentos.' },
  { value: 'BARBERSHOP', title: 'Barbearia', text: 'Corte, barba, combos e acabamentos.' },
];
