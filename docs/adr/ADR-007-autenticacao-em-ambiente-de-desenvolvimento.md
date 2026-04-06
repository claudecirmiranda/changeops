# ADR-007 — Autenticação em Ambiente de Desenvolvimento

**Status:** Aceito (com dívida técnica registrada)
**Data:** Abril/2026
**Decisores:** Equipe Técnica

## Contexto

A arquitetura de produção do ChangeOps utiliza **Keycloak** como Identity Provider, com autenticação via **OAuth2 JWT** validado pelo `change-service` como Resource Server. O `docker-compose.yml` prevê o Keycloak como serviço, mas para a POC ele não está operacional — subir e configurar um IDP completo aumentaria significativamente a barreira de entrada para avaliadores e desenvolvedores.

O `change-service` precisa identificar o usuário responsável pela criação de uma mudança (`requestedBy`). Em produção, esse valor vem do `subject` do JWT. Sem Keycloak ativo, é necessária uma alternativa para o ambiente de desenvolvimento.

## Decisão

Adotamos como **solução temporária** o uso do header `X-User-Id` combinado com lógica condicional baseada no perfil `local` no controller:
```java
private String resolveRequestedBy(String userId, Jwt jwt, String fallback) {
    if (jwt != null && jwt.getSubject() != null) return jwt.getSubject();
    if (environment.acceptsProfiles(Profiles.of("local")) && userId != null) return userId;
    return fallback;
}
```

Essa abordagem permite que chamadas no ambiente de desenvolvimento passem o usuário via header sem necessidade de token JWT válido.

## Problema com a Solução Atual

A solução atual introduz **lógica condicional baseada em perfil no controller** — camada que deveria ser agnóstica ao ambiente de execução. Isso viola o princípio de que código de produção não deve conter desvios de comportamento baseados em perfil fora da camada de infraestrutura/configuração.

Adicionalmente, o `SPRING_PROFILES_ACTIVE=local,docker` necessário para essa solução causou efeito colateral no logback — dois perfis ativos simultaneamente (`local` e `docker`) ativavam dois appenders concorrentes (`STDOUT` e `JSON_STDOUT`), resolvido com a expressão `local &amp; !docker` no `logback.xml`.

## Solução Correta (Dívida Técnica)

A solução arquiteturalmente correta é um **`JwtDecoder` mock via `@Profile("local")`** que aceita qualquer token sem validar a assinatura, mantendo a estrutura JWT intacta:
```java
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    @Bean
    @Primary
    public JwtDecoder mockJwtDecoder() {
        // Aceita qualquer token sem validação de assinatura
        // O subject é extraído normalmente do payload
        return token -> {
            // Parse sem verificação de assinatura
            return NimbusJwtDecoder.withPublicKey(...).build().decode(token);
        };
    }
}
```

Com essa abordagem:
- O controller permanece inalterado — `requestedBy` sempre vem do JWT subject
- O header `X-User-Id` é removido completamente
- O método `resolveRequestedBy` é simplificado para uma única linha
- O perfil `local` no `docker-compose.yml` pode ser removido, ficando apenas `docker`
- O efeito colateral no logback é eliminado na origem

## Impacto da Solução Atual vs. Solução Correta

| Aspecto | Solução atual (workaround) | Solução correta (mock JWT) |
|---|---|---|
| Lógica condicional no controller | ❌ Presente | ✅ Ausente |
| Header `X-User-Id` no código | ❌ Presente | ✅ Removido |
| Perfil `local` no docker-compose | ⚠ Necessário | ✅ Removível |
| Efeito colateral no logback | ⚠ Requer `local &amp; !docker` | ✅ Eliminado |
| Barreira de entrada para avaliadores | ✅ Baixa | ✅ Baixa |
| Conformidade arquitetural | ❌ Viola separação de camadas | ✅ Preservada |
| Esforço de implementação | ✅ Já implementado | ⚠ Requer implementação |

## Consequências da Solução Atual

### Positivas
- Zero barreira de entrada: `make up` + header `X-User-Id` é suficiente para demonstrar o fluxo completo
- Implementação imediata sem dependência de IDP externo
- Funcional para os cenários da POC

### Negativas / Riscos
- Lógica de perfil no controller contamina código de produção
- Header `X-User-Id` sem autenticação é um vetor de identity spoofing se o perfil `local` vazar para produção
- Efeito colateral no logback requer configuração adicional (`local &amp; !docker`)

### Mitigações
- Perfil `local` nunca é ativado em `prod`/`staging` — binding explícito na `SecurityConfig` com `@Profile("!local & !test")`
- Header `X-User-Id` só é aceito quando perfil `local` está ativo — não funciona em produção
- Dívida técnica priorizada para resolução antes do go-live

## Roadmap

**Antes do go-live — implementar Mock JWT:**

1. Criar `LocalJwtDecoderConfig` com `@Profile("local")` e `@Primary`
2. Remover `X-User-Id` header e lógica `resolveRequestedBy` do controller
3. Remover perfil `local` do `SPRING_PROFILES_ACTIVE` no `docker-compose.yml`
4. Simplificar `logback.xml` removendo a expressão `local &amp; !docker`

**Phase 2 — Keycloak operacional:**

1. Ativar serviço Keycloak no `docker-compose.yml`
2. Configurar realm `changeops` com roles `OPERATOR` e `ADMIN`
3. Remover `LocalJwtDecoderConfig` — produção e desenvolvimento usam o mesmo fluxo OAuth2

## Relacionado a
- [ADR-005](./ADR-005-estrutura-pacotes.md) — Perfis de segurança e separação de configuração por ambiente
- [ADR-003](./ADR-003-eventos-dominio-vs-integracao.md) — `correlationId` propagado via `CorrelationIdFilter`, independente do mecanismo de autenticação

## Conformidade com a RFP

| Requisito | Status | Evidência |
|-----------|--------|-----------|
| "Autenticação implementada ou justificadamente desabilitada" | ✅ Atendido | OAuth2 JWT ativo em `prod`/`staging`; workaround documentado para POC com dívida técnica registrada |
| "Ambiente autossuficiente para avaliação" | ✅ Atendido | `make up` + header `X-User-Id` suficiente para demonstrar todos os fluxos sem IDP externo |
| "Segurança não comprometida em produção" | ✅ Atendido | `X-User-Id` só aceito com perfil `local` ativo; binding explícito impede ativação em `prod` |

## Justificativa

A solução com header `X-User-Id` e perfil `local` foi adotada como **decisão deliberada de escopo da POC**, alinhada à RFP que explicita que autenticação e autorização são avaliadas "quando aplicável" (item 11) e que integrações com sistemas externos — incluindo Identity Providers — estão fora do escopo dos cenários definidos (item 21.2.5).

O foco da avaliação recai sobre arquitetura orientada a eventos, qualidade do código e decisões técnicas (item 16), não sobre a implementação completa do stack de segurança. A solução mantém o `CorrelationIdFilter`, rate limiting e CORS ativos mesmo no perfil `local`, preservando os aspectos de segurança relevantes para a demonstração.

A solução correta com mock JWT está documentada no roadmap — não como correção de um erro, mas como evolução natural antes do go-live, quando o Keycloak será ativado e o workaround eliminado na origem.