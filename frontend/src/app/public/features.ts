import { IconName } from '../shared/icon';

export interface Feature {
  title: string;
  text: string;
  icon: IconName;
  /** "soon" is shown with the "Em breve" label: nothing is sold as ready before it is (spec public-site). */
  status: 'available' | 'soon';
}

/** Single source of what the landing page promises. Flip a status when its change ships. */
export const FEATURES: Feature[] = [
  { title: 'Equipe com papéis', text: 'Dono, gerente e profissional: cada um vê só o que precisa.', icon: 'team', status: 'available' },
  { title: 'Serviços e profissionais', text: 'Duração, preço fixo ou "a partir de", quem faz o quê e em que horário.', icon: 'scissors', status: 'available' },
  { title: 'Dados protegidos (LGPD)', text: 'Cada salão isolado no banco. O suporte só vê dados reais com registro e aviso para você.', icon: 'lock', status: 'available' },
  { title: 'Agenda online sem ligação', text: 'Sua cliente marca pelo link do salão, a qualquer hora.', icon: 'calendar', status: 'soon' },
  { title: 'Atendimento em 3 toques', text: '“Ana · Corte · R$ 80 · Pix” — e o histórico fica salvo.', icon: 'check', status: 'soon' },
  { title: 'Caixa e comissões', text: 'Entradas, despesas, fechamento do dia e comissão de cada profissional.', icon: 'cash', status: 'soon' },
  { title: 'Clientes para chamar', text: 'Quem passou do tempo de voltar, com mensagem pronta para o WhatsApp.', icon: 'phone', status: 'soon' },
];

export const FAQ = [
  { q: 'Quanto custa?', a: 'Durante o lançamento o Estilix é gratuito. Você assina o plano de R$ 0,00 e usa tudo o que já está disponível.' },
  { q: 'Serve para barbearia?', a: 'Sim. Na hora do cadastro você escolhe salão de beleza ou barbearia e já recebe serviços sugeridos para o seu tipo.' },
  { q: 'Por que meu cadastro passa por análise?', a: 'A equipe do Estilix confere cada salão antes de liberar, para manter a plataforma segura para você e para suas clientes.' },
  { q: 'Meus dados ficam seguros?', a: 'Cada salão é isolado no banco de dados. Nenhum dado pessoal vai para registros técnicos, e qualquer acesso do suporte a dados reais fica registrado e visível para você.' },
  { q: 'Preciso instalar alguma coisa?', a: 'Não. O Estilix funciona no navegador do celular ou do computador.' },
];

/** Shown on the prerendered plans page before the catalog loads (and if the API is unreachable). */
export const FALLBACK_PLAN = {
  code: 'launch',
  name: 'Lançamento',
  description: 'Tudo liberado e sem custo durante o lançamento do Estilix.',
  price: '0.00',
  priceLabel: 'R$ 0,00/mês',
  features: [
    'Equipe ilimitada com papéis',
    'Serviços, profissionais e horários',
    'Dados protegidos (LGPD)',
    'Agenda online sem ligação (em breve)',
    'Atendimentos em 3 toques (em breve)',
    'Caixa e comissões (em breve)',
    'Clientes para chamar (em breve)',
  ],
};
