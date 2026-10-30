#!/usr/bin/env bash
# Estilix local environment (Git Bash, Linux, macOS).
#
#   ./scripts/local.sh up            core: PostgreSQL, fake login, API, web app (~4 GB RAM)
#   ./scripts/local.sh up full       + the `full` Compose profile (Kafka, Redis, ... in later changes)
#   ./scripts/local.sh status        what is running, URLs and demo users
#   ./scripts/local.sh logs [svc]    follow logs (api, web, postgres, auth)
#   ./scripts/local.sh down          stop everything, keep data
#   ./scripts/local.sh down --wipe   stop and delete local data
set -euo pipefail

cd "$(dirname "$0")/.."

command -v docker > /dev/null || { echo "Docker não encontrado. Instale o Docker Desktop."; exit 1; }
docker info > /dev/null 2>&1 || { echo "O Docker não está rodando. Abra o Docker Desktop e tente de novo."; exit 1; }

compose() { docker compose "$@"; }

print_status() {
  compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
  cat <<'EOF'

URLs
  Site ........... http://localhost:4200  (landing e planos, sem login)
  Agendar ........ http://localhost:4200/agendar/salao-bela-vista  (página pública, sem login)
  App ............ http://localhost:4200/entrar
  API ............ http://localhost:8080  (Swagger: http://localhost:8080/swagger-ui.html)
  Login falso .... http://localhost:8090/default/.well-known/openid-configuration
  PostgreSQL ..... localhost:5441  (banco estilix)

Usuários de demonstração: na tela de login digite só o "username" abaixo.
  dona-bela      Dona do Salão Bela Vista
  gerente-bela   Gerente do Salão Bela Vista
  pro-bela       Profissional do Salão Bela Vista
  dono-ze        Dono da Barbearia do Zé
  dona-nova      Dona do Studio Lia Nails, ainda em análise (tela de espera)
  qualquer-nome  Pessoa nova: vá em Planos > Assinar e cadastre um salão
  suporte        Suporte da plataforma: em "claims" cole {"cognito:groups": ["platform-support"]}
EOF
}

case "${1:-}" in
  up)
    if [ "${2:-}" = "full" ]; then
      echo "Subindo o perfil full..."
      compose --profile full up -d --build --wait
    else
      echo "Subindo o perfil core (banco, login, API e app)..."
      compose up -d --build --wait
    fi
    echo
    print_status
    ;;
  down)
    if [ "${2:-}" = "--wipe" ]; then
      compose --profile full down --volumes --remove-orphans
      echo "Tudo desligado e os dados locais foram apagados."
    else
      compose --profile full down --remove-orphans
      echo "Tudo desligado. Os dados continuam salvos (use 'down --wipe' para apagar)."
    fi
    ;;
  status)
    print_status
    ;;
  logs)
    shift
    compose logs -f --tail=200 "$@"
    ;;
  *)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
