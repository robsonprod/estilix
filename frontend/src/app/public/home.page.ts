import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Icon } from '../shared/icon';
import { FAQ, FEATURES } from './features';
import { PublicFooter, PublicHeader, signedInSignal } from './public-shell';

/** Public landing page (spec marketing/public-site). Prerendered: the text is in the HTML. */
@Component({
  selector: 'app-home',
  imports: [RouterLink, Icon, PublicHeader, PublicFooter],
  template: `
    <app-public-header />
    <main>
      <section class="hero">
        <div class="pub-wrap">
          <span class="eyebrow">Para salões de beleza e barbearias</span>
          <h1>Seu salão organizado. Sem ligação, sem caderninho.</h1>
          <p class="lead">
            A cliente marca pelo link, a equipe registra o atendimento em poucos toques e você sabe quem chamar de volta.
            Comece grátis durante o lançamento.
          </p>
          <div class="row wrap">
            <a routerLink="/planos" class="btn">Ver planos</a>
            <a routerLink="/entrar" class="btn secondary">{{ signedIn() ? 'Abrir o Estilix' : 'Entrar' }}</a>
          </div>
        </div>
      </section>

      <section class="pub-section">
        <div class="pub-wrap two">
          <div class="card">
            <h2>Salão de beleza</h2>
            <p class="muted">Corte, escova, coloração “a partir de”, manicure. Serviços sugeridos já na criação da conta.</p>
          </div>
          <div class="card">
            <h2>Barbearia</h2>
            <p class="muted">Corte, barba, combo e pezinho. Cada barbeiro com a própria agenda e os próprios horários.</p>
          </div>
        </div>
      </section>

      <section class="pub-section">
        <div class="pub-wrap">
          <h2 class="section-title">O que o Estilix faz</h2>
          <div class="features">
            @for (f of features; track f.title) {
              <article class="card feature">
                <div class="row">
                  <span class="feature-icon"><app-icon [name]="f.icon" /></span>
                  @if (f.status === 'soon') { <span class="badge neutral">Em breve</span> } @else { <span class="badge ok">Disponível</span> }
                </div>
                <h3>{{ f.title }}</h3>
                <p class="muted small">{{ f.text }}</p>
              </article>
            }
          </div>
        </div>
      </section>

      <section class="pub-section alt">
        <div class="pub-wrap">
          <h2 class="section-title">Como funciona</h2>
          <ol class="steps">
            <li><strong>Assine grátis</strong><span class="muted">Escolha o plano de lançamento e crie sua conta.</span></li>
            <li><strong>Configure em 3 telas</strong><span class="muted">Tipo do salão, horários e serviços — tudo já vem preenchido.</span></li>
            <li><strong>Nós liberamos</strong><span class="muted">A equipe do Estilix confere os dados e libera seu salão.</span></li>
          </ol>
        </div>
      </section>

      <section class="pub-section">
        <div class="pub-wrap">
          <h2 class="section-title">Perguntas frequentes</h2>
          <div class="faq">
            @for (item of faq; track item.q) {
              <details class="card">
                <summary>{{ item.q }}</summary>
                <p class="muted">{{ item.a }}</p>
              </details>
            }
          </div>
        </div>
      </section>

      <section class="pub-section cta">
        <div class="pub-wrap center">
          <h2>Pronto para organizar o seu salão?</h2>
          <a routerLink="/planos" class="btn">Começar grátis</a>
        </div>
      </section>
    </main>
    <app-public-footer />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomePage {
  protected readonly features = FEATURES;
  protected readonly faq = FAQ;
  protected readonly signedIn = signedInSignal();

  constructor() {
    inject(Title).setTitle('Estilix — gestão para salões de beleza e barbearias');
    inject(Meta).updateTag({
      name: 'description',
      content: 'Agenda online sem ligação, atendimentos em poucos toques e clientes para chamar de volta. Grátis no lançamento.',
    });
  }
}
