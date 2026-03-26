# Resumo dos Ajustes — Sessão de Resolução de Discrepâncias

> **Data:** 26 de março de 2026  
> **Referência:** `RELATORIO_DISCREPANCIAS.md` (9 discrepâncias resolvidas ou justificadas)

---

## Arquivos Modificados

### Código — `change-service`

| Arquivo | Ajuste | Motivação |
|---|---|---|
| `backend/change-service/src/main/java/.../api/dto/CreateChangeRequest.java` | Adicionado `@Size(max = 100)` e `@Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,99}$")` no campo `componentId` | **DISC-12** — `escopo_tecnico.md` §Fluxo 1 CT-03: `componentId` inválido deve retornar 400 |
| `backend/change-service/src/test/java/.../api/CreateChangeIT.java` | Adicionado teste `shouldReturn400_whenComponentIdHasInvalidFormat()` com payload `"?#@invalid"` | **DISC-12** — CT-03 não estava coberto por nenhum teste automatizado |
| `backend/change-service/src/main/java/.../infrastructure/kafka/KafkaEventPublisherAdapter.java` | Adicionado `.tag("type", "ChangePreparedEvent")` ao `Counter.builder` | **DISC-15** — `escopo_tecnico.md` §Observabilidade: métrica sem dimensão por tipo impossibilitava filtros no Prometheus/Grafana |

### Código — `deploy-orchestrator`

| Arquivo | Ajuste | Motivação |
|---|---|---|
| `backend/deploy-orchestrator/src/main/java/.../infrastructure/kafka/KafkaResultPublisherAdapter.java` | Campo `Counter eventsPublishedCounter` substituído por `MeterRegistry meterRegistry`; incremento alterado para `meterRegistry.counter("events_published_total", "type", envelope.eventType()).increment()` | **DISC-15** — O adapter publica dois tipos de evento (`ChangeCompletedEvent` e `ChangeFailedEvent`); counter estático impedia diferenciação por type |
| `backend/deploy-orchestrator/src/main/java/.../infrastructure/kafka/DeployEventConsumer.java` | Método `onDlt()` reescrito com `MDC.put` para `correlation_id`, `change_id` e `deploy_id`; log ampliado para incluir o campo `result`; MDC limpo em bloco `finally` | **DISC-16** — `escopo_tecnico.md` §Logging: logs de erro devem incluir os três campos MDC para rastreabilidade em produção |
| `backend/deploy-orchestrator/src/test/java/.../infrastructure/kafka/KafkaResultPublisherAdapterTest.java` | **Arquivo criado** — dois testes: (1) publicação bem-sucedida incrementa counter com tag correta; (2) falha na publicação aciona `kafkaTemplate.send(dlqTopic, ...)` | **DISC-18** — `escopo_tecnico.md` §Fluxo 2 CT-08: fallback para DLQ de publicação não tinha cobertura de teste |

### Contratos e Infraestrutura

| Arquivo | Ajuste | Motivação |
|---|---|---|
| `contracts/openapi/change-service.yml` | Adicionados `minLength: 1` e `pattern: "^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,99}$"` no schema de `componentId` | **DISC-12** — Contrato OpenAPI estava inconsistente com a validação adicionada no DTO |
| `infra/grafana/dashboards/changeops.json` | Query `rate(events_published_total[1m])` alterada para `sum(rate(events_published_total[1m]))` | **DISC-15** — Com a tag `type` introduzida, `rate()` sozinha retorna séries por instância/tag; `sum()` agrupa corretamente |

### Documentação — `docs/`

| Arquivo | Ajuste | Motivação |
|---|---|---|
| `docs/adr/ADR-003-eventos-dominio-vs-integracao.md` | Adicionadas duas seções ao final: `## Padrões de Design Aplicados` (Adapter, Envelope, Tag-based Metric) e `## Compatibilidade com Versões Anteriores` (regras de breaking/non-breaking change, tolerant reader, field `version`) | **DISC-23** e **DISC-24** — `escopo.md` §5: design patterns devem ser documentados explicitamente; `escopo.md` §5.3: estratégia de backward compatibility deve estar formalizada em ADR |
| `docs/PROJETO_COMPLETO.md` | (1) Bloco de código de `CreateChangeRequest` atualizado com `@Pattern`; (2) Teste CT-03 adicionado à listagem de `CreateChangeIT`; (3) Snippet de `onDlt()` atualizado para versão com MDC; (4) Linha da métrica `events_published_total` atualizada para `{type="..."}` | Sincronização com os ajustes de código em DISC-12, DISC-16 e DISC-15 |
| `docs/PREPARACAO_APRESENTACAO.md` | `events_published_total` → `events_published_total{type="..."}` na tabela de métricas | Sincronização com DISC-15 |
| `docs/test-strategy.md` | Adicionada linha `KafkaResultPublisherAdapterTest` na tabela de testes unitários do `deploy-orchestrator` | Sincronização com DISC-18 |
| `docs/ROADMAP.md` | Adicionada seção `## Itens de Processo Pendentes` com tabela registrando DISC-13 (Registro de Defeitos), DISC-19 (Kanban) e DISC-20 (Vídeo Demo) como backlog pós-aprovação | **DISC-13**, **DISC-19**, **DISC-20** — Critérios §7.1 e §7.2 do `escopo.md` atendidos sem criar novos arquivos de documentação |
| `README.md` | Adicionada seção `## Demonstração` com placeholder para vídeo e referência ao `ROTEIRO_TESTES_MANUAIS.md` | **DISC-20** — `escopo.md` §7.2: artefato de demonstração deve existir no repositório |

---

## Resultado dos Testes

| Serviço | Testes | Resultado |
|---|:---:|:---:|
| `change-service` | 43 | ✅ BUILD SUCCESS |
| `deploy-orchestrator` | 17 (inclui 2 novos de `KafkaResultPublisherAdapterTest`) | ✅ BUILD SUCCESS |

---

## Referência Cruzada DISC → Arquivos

| DISC | Arquivos de código modificados | Documentos atualizados |
|:---:|---|---|
| DISC-12 | `CreateChangeRequest.java`, `CreateChangeIT.java`, `change-service.yml` | `PROJETO_COMPLETO.md` |
| DISC-13 | — | `ROADMAP.md` §Itens de Processo Pendentes |
| DISC-15 | `KafkaEventPublisherAdapter.java`, `KafkaResultPublisherAdapter.java`, `changeops.json` | `PROJETO_COMPLETO.md`, `PREPARACAO_APRESENTACAO.md` |
| DISC-16 | `DeployEventConsumer.java` | `PROJETO_COMPLETO.md` |
| DISC-18 | `KafkaResultPublisherAdapterTest.java` (criado) | `test-strategy.md` |
| DISC-19 | — | `ROADMAP.md` §Itens de Processo Pendentes |
| DISC-20 | — | `README.md` §Demonstração, `ROADMAP.md` §Itens de Processo Pendentes |
| DISC-23 | — | `ADR-003` §Padrões de Design Aplicados |
| DISC-24 | — | `ADR-003` §Compatibilidade com Versões Anteriores |
