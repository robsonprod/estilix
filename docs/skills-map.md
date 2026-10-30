# Skills map

Every technology in Estilix enters through a salon problem, not the other way around. This page maps each skill to the business case that justifies it, the OpenSpec change that delivers it, and the evidence a reviewer can check. Status is updated as changes are archived.

Legend: ✅ built · 🔨 in progress · 📋 planned · 📝 documented trade-off only (no business case to build it)

## AI engineering

| Skill | Business case in Estilix | Change | Evidence | Status |
|---|---|---|---|---|
| AI agents (LangGraph, PydanticAI) | Owner asks the assistant "who should I call this week and what do I say?"; the agent queries the API with the owner's permissions and drafts messages | `salon-assistant-agents` | Agent graph diagram, tool calls scoped to one tenant, tests with recorded LLM responses | 📋 |
| Multi-agent, Plan-and-Execute | "Prepare a reactivation campaign for clients missing 45+ days": a planner splits it into segment → draft → schedule, executors run each step | `salon-assistant-agents` | Trace of plan and steps in Langfuse; plan rejected when a step needs a permission the user lacks | 📋 |
| Reflexion | Message drafts are criticized by a reviewer node (tone, length, no sensitive data, no invented prices) and rewritten up to N times | `salon-assistant-agents` | Eval set: % of drafts passing the critic before and after reflexion | 📋 |
| Human-in-the-loop | Nothing is sent to a customer without the owner approving it | `salon-assistant-agents` | LangGraph interrupt + resume, persisted in MongoDB checkpointer | 📋 |
| Advanced RAG | Staff ask "how do I close the cash register?" (help center); customers on the booking page ask "do you do keratin treatment, how much?" (salon catalog) | `help-center-rag` | Hybrid retrieval (pgvector + BM25 in Elasticsearch), reranking, query rewriting, mandatory tenant filter, citations, RAGAS-style evals in CI | 📋 |
| NLP | "Quero cortar sábado de manhã com o João" becomes a booking request (service, professional, date range); PII detection before any prompt | `natural-language-booking`, `ai-gateway-and-guardrails` | Structured output validated by Pydantic; entity extraction F1 on a labeled pt-BR set | 📋 |
| Prompt engineering | Versioned prompts with few-shot examples in pt-BR salon jargon ("luzes", "progressiva", "pezinho") | all AI changes | Prompts in the repo, changes gated by eval regression | 📋 |
| Fine-tuning (LoRA / QLoRA) | Decide if a small open model fine-tuned on salon jargon beats prompting a large model for booking extraction, at lower cost/latency | `natural-language-booking` | ADR with the decision matrix (prompt → RAG → fine-tune), QLoRA run on Hugging Face, eval comparison table | 📋 |
| ML (classic) | Churn risk: probability a customer will not come back, to rank the "customers to call back" list | `customer-retention` | Feature pipeline from service records, gradient boosting baseline, tracked in MLflow, model card | 📋 |
| Deep learning | Multilingual sentence embeddings for RAG and semantic customer notes search | `help-center-rag` | Embedding model choice benchmarked on pt-BR queries | 📋 |
| Hugging Face, Ollama | Ollama runs models locally at zero cost for development; Hugging Face for embeddings and the LoRA experiment | AI changes | `docker compose --profile full` runs Ollama; model ids pinned | 📋 |
| LangChain, CrewAI | LangChain for retrieval components; CrewAI compared against LangGraph | `salon-assistant-agents` | ADR "LangGraph vs CrewAI": explicit state + interrupts won | 📝 CrewAI |
| Guardrails | Public booking chat is open to anyone: block prompt injection, off-topic use, PII leaks and invented prices | `ai-gateway-and-guardrails` | Input/output guards with tests | 📋 |
| Red teaming | Attack suite (injection, jailbreak, cross-tenant data extraction, PII extraction) runs in CI | `ai-gateway-and-guardrails` | promptfoo report in CI; build fails on regression | 📋 |
| AI gateway | One door for every LLM call: model routing (Ollama local / Bedrock in AWS), fallback, cache, per-plan monthly AI quota per salon | `ai-gateway-and-guardrails` | LiteLLM config, quota test, cost per tenant dashboard | 📋 |
| LLM observability and curation | See every prompt, tool call, latency and cost; turn bad traces into eval cases | `ai-gateway-and-guardrails` | OpenTelemetry traces into Langfuse; MLflow for model/eval runs | 📋 |

## Back-end, data and messaging

| Skill | Business case | Change | Evidence | Status |
|---|---|---|---|---|
| PostgreSQL (advanced) | Many salons in one database without leaks | `platform-foundation` | Row-Level Security, guard test for unprotected tables | 🔨 |
| Kafka | A recorded service must update finance, retention and search without the cashier waiting for all of them | `event-backbone` | Transactional outbox, partition by tenant, consumer groups | 📋 |
| Delivery guarantees and retry | No lost "service recorded" event, no double commission | `event-backbone`, `notifications` | At-least-once + idempotent consumers, exponential backoff with jitter, dead-letter topic/queue, replay tool | 📋 |
| Redis | Public booking page and OTP codes are open to the internet: limit abuse; cache availability | `scheduling` | Token-bucket rate limit per IP and per phone, 429 Problem Details | 📋 |
| Rate limiting | Same as above + AI quota per plan | `scheduling`, `ai-gateway-and-guardrails` | Tests for burst and refill | 📋 |
| Circuit breaker | E-mail provider or LLM slow → the agenda must stay fast | `notifications`, AI changes | Resilience4j breaker, timeout, bulkhead, fallback; chaos test with Toxiproxy | 📋 |
| Health checks | Orchestrator restarts only broken processes and stops routing to unready ones | `platform-foundation` | Liveness vs readiness, dependency indicators | 🔨 |
| Elasticsearch | Find "Ana Paula" typed as "ana paul" or by the last 4 phone digits; BM25 side of hybrid RAG | `customer-search`, `help-center-rag` | Index fed by events (CQRS read model), tenant-scoped queries | 📋 |
| MongoDB (Atlas / self-hosted) | Agent conversations and checkpoints: nested, schema-flexible, written often, read by thread | `salon-assistant-agents` | LangGraph MongoDB checkpointer; Atlas free tier on AWS, container locally | 📋 |
| Cassandra, HBase, Cosmos DB | No workload in Estilix needs them at this scale | — | ADR "when we would pick a wide-column store" (e.g. audit at billions of rows, multi-region writes) | 📝 |

## Front-end, cloud and DevOps

| Skill | Business case | Change | Evidence | Status |
|---|---|---|---|---|
| Micro front-end (Angular + Native Federation) | The booking widget is embedded in the salon's own site/Instagram link and deployed independently from the back office; the support console is a separate remote | `booking-widget-microfrontend` | Shell + remotes with shared design system, version skew test | 📋 |
| AWS (S3, SQS, ECS, RDS, Cognito, SES) | Production-like environment that costs cents when idle | `aws-deployment`, `notifications` | Terraform layers, `scripts/aws.sh up/down/status` | 📋 |
| Kubernetes, EKS | Same images on Kubernetes: local k3d always, EKS only on demo day | `kubernetes-deployment` | Helm charts, probes, HPA; EKS created and destroyed by script | 📋 |
| CI/CD | Every PR runs tests, evals and red-team suite; main deploys via GitHub OIDC (no keys) | all | GitHub Actions workflows | 🔨 |
| OpenTelemetry | One trace from Angular click → Kotlin API → Kafka → Python AI service → LLM | `event-backbone`, AI changes | Trace screenshot and collector config | 📋 |

## Trade-offs written down

Each one becomes an ADR when its change lands:

- Modular monolith + one Python AI service instead of many microservices — one developer, one database, clear module boundaries; the AI service exists because the ecosystem is Python.
- Kafka locally and on Kubernetes, but a single-broker container on AWS demos instead of MSK — MSK has a fixed hourly cost that breaks the "idle for cents" goal.
- EKS only on demand — the control plane alone costs about US$ 73/month.
- Fine-tuning last — prompt, then RAG, then fine-tune only if evals prove a gain worth the cost.
