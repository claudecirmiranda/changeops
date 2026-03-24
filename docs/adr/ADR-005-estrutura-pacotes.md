# ADR-005 — Estrutura de Pacotes e Arquitetura

**Status:** Aceito  
**Data:** Março/2026  
**Decisores:** Equipe Técnica

---

## Contexto

A RFP valoriza "organização por domínio (Domain-Driven Design lite)" e "estrutura de pacotes organizada". É necessário definir como os serviços backend serão estruturados internamente, garantindo separação de responsabilidades, testabilidade e baixo acoplamento.

Além disso, o domínio define o status `DRAFT` como parte do ciclo de vida da mudança, mas na implementação atual a criação vai diretamente para `PREPARED`. É necessário documentar essa decisão.

---

## Decisão

Adotamos **Arquitetura Hexagonal (Ports & Adapters)** com pacotes organizados por camada dentro de cada bounded context.

### Estrutura de Pacotes

```
com.changeops.changeservice/
├── api/                          # Adaptadores de entrada (driving)
│   ├── controller/               # REST controllers
│   └── dto/                      # Request/Response DTOs
├── application/                  # Lógica de aplicação
│   ├── port/
│   │   ├── in/                   # Use cases (driving ports)
│   │   └── out/                  # Driven ports (interfaces)
│   └── service/                  # Implementação dos use cases
├── domain/                       # Núcleo de domínio (zero dependências externas)
│   ├── model/                    # Aggregates, entidades
│   ├── event/                    # Eventos de domínio
│   ├── exception/                # Exceções de domínio
│   └── valueobject/              # Value objects (ChangeStatus)
└── infrastructure/               # Adaptadores de saída (driven)
    ├── config/                   # Configurações Spring/Kafka
    ├── kafka/                    # Publisher adapter + IntegrationEvent
    ├── observability/            # Filtros MDC, métricas
    ├── persistence/              # JPA entities, repositories, adapters
    ├── ratelimit/                # Rate limiting
    └── security/                 # OAuth2, CORS
```

### Regra de Dependência

```
api → application → domain ← infrastructure
         ↓
    port/in  port/out
                ↑
          infrastructure
```

- **Domain** não depende de nenhuma outra camada (zero imports de Spring, JPA, Kafka).
- **Application** depende apenas de `domain` e define interfaces (`port/out`) que a infraestrutura implementa.
- **Infrastructure** implementa os ports de saída e não é referenciada diretamente pelo application.
- **API** é um adaptador de entrada que invoca use cases via ports de entrada.

### Status DRAFT

O enum `ChangeStatus` inclui o valor `DRAFT`, porém a implementação atual da factory `Change.create()` define o status diretamente como `PREPARED`. Essa é uma **decisão deliberada**:

- No fluxo da POC, não há etapa de rascunho ou aprovação antes da preparação.
- O status `DRAFT` é **reservado para uso futuro** — cenários como "criação em rascunho com aprovação posterior" ou "edição antes de submissão".
- Manter `DRAFT` no enum garante compatibilidade forward (não será necessário alterar o schema ou a API quando implementado).
- A transição `DRAFT → PREPARED` será adicionada quando o fluxo de aprovação for implementado (Roadmap futuro).

---

## Alternativas Consideradas

### 1. Hexagonal / Ports & Adapters (escolhida)

- Inversão de dependências clara: domínio no centro, sem acoplamento a frameworks.
- Testabilidade máxima: serviços testados com mocks dos ports de saída.
- Substituição de adapters sem impacto no domínio (ex: trocar Kafka por RabbitMQ).
- Trade-off: mais interfaces e classes que uma abordagem layered simples.

### 2. Camadas tradicionais (Controller → Service → Repository)

- Simples e familiar para a maioria dos desenvolvedores.
- Services tendem a acumular lógica (god classes).
- Repository acoplado ao framework (JPA direto no service).
- Menor testabilidade: mocks de repositórios JPA são mais complexos.

### 3. Organização por feature/domínio (vertical slicing)

- Cada feature em um pacote independente (ex: `create-change/`, `list-changes/`).
- Boa para microserviços muito grandes com múltiplos subdomínios.
- Over-engineering para serviços de escopo focado como os desta POC.
- Dificulta visualização da arquitetura em camadas.

---

## Trade-offs

| Aspecto | Hexagonal (escolhida) | Camadas tradicionais | Vertical slicing |
|---------|----------------------|---------------------|------------------|
| Desacoplamento | ✅ Máximo | ❌ Baixo | ⚠ Médio |
| Testabilidade | ✅ Mock de ports | ⚠ Mock de repos JPA | ✅ Boa |
| Complexidade | ⚠ Mais interfaces | ✅ Mínima | ⚠ Alta |
| Substituição de infra | ✅ Via adapter | ❌ Requer refactor | ⚠ Parcial |
| Curva de aprendizado | ⚠ Requer entendimento de ports | ✅ Intuitiva | ⚠ Conceitual |
| Escalabilidade de código | ✅ Boa separação | ⚠ God services | ✅ Boa |

**Justificativa:** A Hexagonal Architecture oferece o melhor equilíbrio entre testabilidade, desacoplamento e manutenibilidade para serviços de domínio focado. O custo adicional de interfaces (ports) é compensado pela qualidade dos testes (mocks precisos) e pela capacidade de evoluir infraestrutura sem impactar o núcleo de negócio.
