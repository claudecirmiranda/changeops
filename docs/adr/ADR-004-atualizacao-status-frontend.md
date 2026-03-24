# ADR-004 — Estratégia de Atualização de Status no Frontend

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

---

## Contexto

O frontend precisa refletir mudanças de status (`PREPARED → COMPLETED` ou `FAILED`) originadas do Fluxo 2 (deploy-orchestrator). Como a transição acontece de forma assíncrona via Kafka, o frontend não é notificado diretamente. A RFP sugere **polling ou SSE** como mecanismo de atualização.

---

## Decisão

Adotamos **polling HTTP a cada 5 segundos** com refresh silencioso (sem loading spinner em background).

### Implementação

```typescript
// shared/hooks/usePolling.ts
usePolling(() => fetchChanges(currentPage), { interval: 5_000, enabled: !!page })
```

- **Intervalo:** 5s (configurável).
- **Comportamento:** Refresh silencioso — sem indicador de carregamento, atualiza dados em background.
- **Resiliência:** Em caso de falha, exibe banner de aviso ("Live updates paused") e continua tentando.
- **Otimização:** Usa `setTimeout` recursivo (não `setInterval`) para evitar sobreposição de requests.

---

## Alternativas Consideradas

### 1. Polling HTTP (escolhida)

- Simples de implementar e depurar.
- Sem dependência de infraestrutura adicional.
- Funciona com qualquer proxy/CDN (HTTP puro).
- Carga previsível no backend (~12 req/min/cliente).
- Latência máxima: intervalo de polling (5s).

### 2. Server-Sent Events (SSE)

- Push real-time do servidor — latência sub-segundo.
- Conexão persistente: requer gerenciamento de reconexão.
- Complexidade no backend: endpoint SSE dedicado com `SseEmitter` ou similar.
- Problemas com proxies/load balancers que fecham conexões idle.
- Consumo de recursos: uma conexão aberta por cliente.

### 3. WebSocket

- Bidirecional — adequado para interações em tempo real.
- Overhead de protocolo (handshake, heartbeat).
- Maior complexidade de infraestrutura (upgrade HTTP → WS).
- Over-engineering para o cenário atual (frontend é read-only após criação).

---

## Trade-offs

| Aspecto | Polling (escolhida) | SSE | WebSocket |
|---------|---------------------|-----|-----------|
| Latência | ⚠ Até 5s | ✅ Sub-segundo | ✅ Sub-segundo |
| Complexidade frontend | ✅ Baixa | ⚠ Média | ⚠ Alta |
| Complexidade backend | ✅ Nenhuma adicional | ⚠ Endpoint SSE | ⚠ Servidor WS |
| Carga no backend | ⚠ N req/min/cliente | ✅ Conexão idle | ✅ Conexão idle |
| Proxy/CDN compatível | ✅ Total | ⚠ Requer suporte | ⚠ Requer suporte |
| Reconexão | ✅ Automática (HTTP) | ⚠ Manual | ⚠ Manual |
| Escalabilidade | ⚠ Linear com clientes | ⚠ Conexões abertas | ⚠ Conexões abertas |

**Justificativa:** Polling é a abordagem mais simples e robusta para a POC. A latência de até 5s é aceitável para um dashboard de mudanças (não é real-time transacional). A migração para SSE está planejada no Roadmap (Phase 2) quando a base de clientes justificar a redução de carga.

O hook `usePolling` é genérico e encapsulado — a migração futura para SSE requer apenas substituir a implementação interna sem impactar componentes consumidores.
