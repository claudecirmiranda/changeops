# ADR-004 — Estratégia de Atualização de Status no Frontend

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

## Contexto

O frontend precisa refletir mudanças de status (`PREPARED → COMPLETED` ou `FAILED`) originadas do Fluxo 2 (deploy-orchestrator). Como a transição acontece de forma assíncrona via Kafka, o frontend não é notificado diretamente. A RFP sugere **polling ou SSE** como mecanismo de atualização.

## Decisão

Adotamos **polling HTTP a cada 5 segundos** com refresh silencioso (sem loading spinner em background).

## Justificativa de SLO

O intervalo de **5 segundos** foi definido como um balanço entre:
*   **Frescor dos dados**: atualização quase em tempo real para o usuário
*   **Carga no backend**: aproximadamente **12 requisições por minuto por cliente**

Esse trade-off é mensurável e observável via métricas (ex: requests por segundo, latência média e erro por endpoint), permitindo ajustes dinâmicos conforme:
*   Crescimento da base de usuários
*   Capacidade do backend
*   Requisitos de experiência do usuário

## Implementação

```TypeScript
// shared/hooks/usePolling.ts  
usePolling(() => fetchChanges(currentPage), { interval: 5_000, enabled: !!page })
```

*   **Intervalo:** 5s (configurável).
*   **Comportamento:** Refresh silencioso — sem indicador de carregamento, atualiza dados em background.
*   **Resiliência:** Em caso de falha, exibe banner de aviso ("Live updates paused") e continua tentando.

## Implementação Resiliente

O hook `usePolling` utiliza **`setTimeout` recursivo** ao invés de `setInterval`.
Isso garante:
*   Evita sobreposição de requisições em cenários de alta latência
*   Garante que uma nova chamada só ocorre após a finalização da anterior
*   Reduz pressão desnecessária no backend
Esse padrão melhora a estabilidade do sistema sob carga e evita efeitos de _thundering herd_ em casos de degradação.

## Alternativas Consideradas

### 1. Polling HTTP (escolhida)

*   Simples de implementar e depurar.
*   Sem dependência de infraestrutura adicional.
*   Funciona com qualquer proxy/CDN (HTTP puro).
*   Carga previsível no backend (~12 req/min/cliente).
*   Latência máxima: intervalo de polling (5s).

### 2. Server-Sent Events (SSE)

*   Push real-time do servidor — latência sub-segundo.
*   Conexão persistente: requer gerenciamento de reconexão.
*   Complexidade no backend: endpoint SSE dedicado com `SseEmitter` ou similar.
*   Problemas com proxies/load balancers que fecham conexões idle.
*   Consumo de recursos: uma conexão aberta por cliente.

### 3. WebSocket

*   Bidirecional — adequado para interações em tempo real.
*   Overhead de protocolo (handshake, heartbeat).
*   Maior complexidade de infraestrutura (upgrade HTTP → WS).
*   Over-engineering para o cenário atual (frontend é read-only após criação).

## Trade-offs

| Aspecto | Polling (escolhida) | SSE | WebSocket |
| --- | --- | --- | --- |
| Latência | ⚠ Até 5s | ✅ Sub-segundo | ✅ Sub-segundo |
| Complexidade frontend | ✅ Baixa | ⚠ Média | ⚠ Alta |
| Complexidade backend | ✅ Nenhuma adicional | ⚠ Endpoint SSE | ⚠ Servidor WS |
| Carga no backend | ⚠ N req/min/cliente | ✅ Conexão idle | ✅ Conexão idle |
| Proxy/CDN compatível | ✅ Total | ⚠ Requer suporte | ⚠ Requer suporte |
| Reconexão | ✅ Automática (HTTP) | ⚠ Manual | ⚠ Manual |
| Escalabilidade | ⚠ Linear com clientes | ⚠ Conexões abertas | ⚠ Conexões abertas |

## Consequências

### Positivas
- Simplicidade inicial: polling com `setTimeout` recursivo é fácil de implementar, testar e debugar
- Evolução transparente: hook `useChangeStatus` abstrai mecanismo, permitindo migração para SSE/WebSocket sem impacto nos componentes
- Controle de recursos: intervalo dinâmico (5s → 30s) reduz consumo de bateria e rede em clientes móveis

### Negativas / Riscos
- Latência de atualização: status pode levar até 5 segundos para refletir mudança no backend, impactando UX em cenários de alta expectativa
- Overhead de requisições: polling contínuo gera ~12 req/min/cliente; pode escalar para milhares de req/s em base grande de usuários
- Edge cases de UX: navegação entre abas, background tabs e perda de foco podem interromper polling sem feedback claro

### Mitigações
- SLO definido: "95% das atualizações refletidas em < 10s" justifica intervalo de 5s para POC; reavaliação na Fase 2
- Hook abstrato permite ajuste centralizado de intervalo; métrica `frontend_polling_requests_total` monitora volume
- Documentação de edge cases em `useChangeStatus.ts` com recomendações para `visibilitychange` e `beforeunload`

## Ausência de Endpoint PUT/PATCH para Mutação Direta de Status

A API expõe `POST /api/v1/changes` (criação) e `GET /api/v1/changes` (leitura), mas **não expõe `PUT` ou `PATCH` para alteração direta de status**. Essa é uma **decisão arquitetural intencional**, não uma lacuna.

### Rationale

No modelo event-driven adotado, o status de uma mudança é uma **consequência de eventos**, não um campo editável diretamente:

```
Status PREPARED  → definido por Change.create() no momento da criação
Status COMPLETED → consequência de DeployFinishedEvent com sucesso
Status FAILED    → consequência de DeployFinishedEvent com falha
Status CANCELLED → único caso com comando direto (roadmap)
```

Permitir `PATCH /changes/{id}/status` criaria dois caminhos para a mesma transição de estado — o evento Kafka e o comando HTTP direto — gerando inconsistência entre o estado do banco e o event log. Isso violaria o princípio de **single source of truth** para transições de estado.

### O que existe em vez disso

- Transições de estado via eventos: `DeployFinishedEvent → COMPLETED/FAILED`
- Timeline completo de eventos via `GET /api/v1/changes/{id}/events` — auditoria sem mutação direta
- **Roadmap (Phase 2):** `POST /api/v1/changes/{id}/cancel` — único comando direto planejado, pois cancelamento é uma intenção do operador, não uma consequência de evento externo

### Relacionado a
- [ADR-003](./ADR-003-eventos-dominio-vs-integracao.md) — `correlationId` do envelope é incluído em respostas HTTP para polling
- [ADR-005](./ADR-005-estrutura-pacotes.md) — Hook `useChangeStatus` vive em `frontend/hooks/`, isolado de lógica de domínio
- [ADR-001](./ADR-001-escolha-message-broker.md) — Eventos assíncronos no Kafka justificam necessidade de polling no frontend

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Mecanismo de atualização de status para frontend" | ✅ Atendido | Hook `useChangeStatus` com polling configurável e tratamento de erro |
| "Propagação de correlation ID para rastreabilidade" | ✅ Atendido | `correlationId` incluído em headers de resposta e logs estruturados |
| "Estratégia evolutiva para comunicação em tempo real" | ✅ Atendido | Hook abstrato permite migração para SSE/WebSocket sem refatoração de componentes |
| "Tratamento de erro e retry no cliente" | ✅ Atendido | Backoff exponencial no polling para erros 5xx; parada para erros 4xx |
| "Monitoramento de comportamento do frontend" | ✅ Atendido | Métrica `frontend_polling_interval_seconds` enviada via endpoint de telemetria |
| "Ausência de PUT/PATCH para status (justificativa)" | ✅ Atendido | Transição de status é event-driven por design; mutação direta violaria single source of truth — documentado neste ADR |

## Justificativa

Polling é a abordagem mais simples e robusta para a POC. A latência de até 5s é aceitável para um dashboard de mudanças (não é real-time transacional). A escolha prioriza previsibilidade operacional e baixo acoplamento com infraestrutura.
O hook `usePolling` é genérico e encapsulado — a migração futura para SSE requer apenas substituir a implementação interna sem impactar componentes consumidores.

## Roadmap

Está prevista a evolução para **Server-Sent Events (SSE)** na Phase 2, quando:
*   O volume de clientes tornar o polling custoso
*   Houver necessidade de latência menor (quase real-time)
*   A infraestrutura suportar conexões persistentes de forma estável
