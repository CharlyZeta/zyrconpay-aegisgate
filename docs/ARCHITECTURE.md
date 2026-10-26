# 🏗️ AegisGate Software Architecture & Distributed Systems Design

[![Java 21](https://img.shields.io/badge/Java-21%2B%20Virtual%20Threads-orange.svg?style=for-the-badge&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.4.x-brightgreen.svg?style=for-the-badge&logo=springboot)](https://spring.io/projects/spring-boot)
[![Kafka KRaft](https://img.shields.io/badge/Apache%20Kafka-KRaft-black.svg?style=for-the-badge&logo=apachekafka)](https://kafka.apache.org/)
[![Redis Hashtags](https://img.shields.io/badge/Redis-7%20Cluster%20Hashtags-red.svg?style=for-the-badge&logo=redis)](https://redis.io/)

Este manual describe la arquitectura de software, patrones de diseño distribuido, justificaciones tecnológicas de ingeniería y topologías físicas de red de **AegisGate 3DS Verification Engine**.

---

## 📋 Tabla de Contenidos
1. [🧠 Filosofía Arquitectónica y Principios de Diseño](#1-filosofía-arquitectónica-y-principios-de-diseño)
2. [🔬 Justificación de Decisiones Tecnológicas](#2-justificación-de-decisiones-tecnológicas)
   - [Java 21 Virtual Threads (Project Loom)](#-java-21-virtual-threads-project-loom)
   - [Spring WebFlux (Ingress Stateless Reactivo)](#-spring-webflux-ingress-stateless-reactivo)
   - [Apache Kafka KRaft Event Bus](#-apache-kafka-kraft-event-bus)
   - [Redis Cluster Hashtags `{transactionId}` & Scripts Lua](#-redis-cluster-hashtags-transactionid--scripts-lua)
   - [Caffeine Cache (`refreshAfterWrite`) & Dynamic Vault](#-caffeine-cache-refreshafterwrite--dynamic-vault)
3. [📊 Diagramas de Arquitectura Mermaid](#3-diagramas-de-arquitectura-mermaid)
   - [Diagrama de Arquitectura Lógica Multimódulo](#-diagrama-de-arquitectura-lógica-multimódulo)
   - [Diagrama de Secuencia de Datos (Happy Path & Late Binding)](#-diagrama-de-secuencia-de-datos-happy-path--late-binding)
   - [Diagrama de Topología Física y Seguridad de Red (DMZ vs Private Subnet)](#-diagrama-de-topología-física-y-seguridad-de-red-dmz-vs-private-subnet)
4. [⚡ Atributos de Calidad y Rendimiento (NFRs)](#4-atributos-de-calidad-y-rendimiento-nfrs)

---

## 1. 🧠 Filosofía Arquitectónica y Principios de Diseño

AegisGate fue concebido para resolver la fragilidad de las integraciones 3DS monolíticas mediante tres pilares fundamentales:

1. **Event-Driven Event Sourcing en Memoria**: En lugar de consultar una base de datos relacional pesada para validar el estado de una transacción, AegisGate modela la autenticación como un conjunto (*Set*) de eventos inmutables que deben converger en memoria ($O(1)$).
2. **Desacoplamiento Estricto de Capas (DMZ vs Core Engine)**: La capa de entrada de peticiones públicas (*Ingress Gateway*) no posee acceso a bases de datos relacionales ni almacena estado persistentemente; únicamente valida firmas criptográficas y publica mensajes en la cola distribuida.
3. **Resiliencia Atómica Anti-Bypass**: Ninguna transacción puede marcarse como autorizable sin la convergencia simultánea del token de checkout intent (`PAYMENT_INTENT_CREATED`) y la confirmación del banco emisor (`3DS_WEBHOOK_RECEIVED:SUCCESS`).

```
                               ┌──────────────────────────┐
                               │  Client / Merchant Front │
                               └────────────┬─────────────┘
                                            │
                    ┌───────────────────────┴───────────────────────┐
                    │                                               │
                    v (POST /intents)                               v (POST /webhooks)
      ┌──────────────────────────┐                    ┌──────────────────────────┐
      │  AegisGate Ingress Gateway│                   │  AegisGate Ingress Gateway│
      │  (Stateless WebFlux)     │                    │  (Stateless WebFlux)     │
      └─────────────┬────────────┘                    └─────────────┬────────────┘
                    │                                               │
                    │ Publish (payment-intents)                     │ Publish (payment-webhooks)
                    └───────────────────────┬───────────────────────┘
                                            │
                                            v
                              ┌───────────────────────────┐
                              │  Apache Kafka Event Bus   │
                              └─────────────┬─────────────┘
                                            │
                                            v Consume (Virtual Threads)
                              ┌───────────────────────────┐
                              │ State Orchestrator Engine │
                              └─────────────┬─────────────┘
                                            │
                                            v EVAL converge.lua
                              ┌───────────────────────────┐
                              │    Redis Cluster Set      │
                              │ payment:3ds:events:{txId} │
                              └───────────────────────────┘
```

---

## 2. 🔬 Justificación de Decisiones Tecnológicas

### 🚀 Java 21 Virtual Threads (Project Loom)
* **Problema**: El modelo tradicional de concurrencia basado en hilos de plataforma (1:1 con hilos del SO) sufre de un costo elevado en context switching y consumo de memoria (1-2 MB por hilo). Bajo alta concurrencia de checkout (ej. CyberMonday), agotar el thread pool bloquea los endpoints.
* **Solución**: AegisGate habilita `spring.threads.virtual.enabled=true` en el microservicio `aegisgate-orchestrator`. Los hilos virtuales son gestionados por la JVM y parkean automáticamente durante I/O bloqueante (como llamadas de red a Redis o Kafka) sin bloquear el *carrier thread* subyacente del sistema operativo.
* **Impacto**: Capacidad de manejar >50,000 transacciones concurrentes con un uso de memoria footprint drásticamente reducido.

---

### ⚡ Spring WebFlux (Ingress Stateless Reactivo)
* **Problema**: El punto de entrada a la red debe recibir notificaciones de red de baja latencia sin convertirse en un cuello de botella bajo ataques de denegación de servicio (DoS) o picos de tráfico.
* **Solución**: `aegisgate-ingress` utiliza **Spring WebFlux** sobre Netty. Es completamente stateless (sin sesión HTTP) y posee cero conexiones a bases de datos relacionales (JPA/Hibernate).
* **Impacto**: Tiempo de respuesta en el borde **P99 < 10 ms**. Tras la validación criptográfica en memoria, la petición responde `HTTP 202 Accepted` de inmediato de forma no bloqueante.

---

### 📦 Apache Kafka KRaft Event Bus
* **Problema**: Las notificaciones asincrónicas de webhooks adquirentes (Payway) y las intenciones de pago del comercio pueden llegar fuera de orden o desincronizadas en el tiempo.
* **Solución**: AegisGate utiliza **Apache Kafka en modo KRaft** (sin dependencia de ZooKeeper). Los eventos `PaymentIntentEvent` y `WebhookReceivedEvent` se publican en tópicos particionados por la clave `transactionId`.
* **Impacto**: Mantiene el orden garantizado de eventos por transacción y permite desacoplar los picos de tráfico del Ingress frente a la capacidad de cómputo del Orchestrator.

---

### 🛡️ Redis Cluster Hashtags `{transactionId}` & Scripts Lua
* **Problema**: En clústeres de Redis distribuidos, ejecutar operaciones multi-clave o evaluar scripts Lua requiere que todas las claves involucradas residan en el mismo nodo/shard. De lo contrario, Redis lanza un error `CROSSSLOT Keys in request don't hash to the same slot`.
* **Solución**: El servicio [`RedisStateService.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-orchestrator/src/main/java/com/zyrconpay/aegisgate/orchestrator/service/RedisStateService.java) emplea la convención de **Hashtags de Redis Cluster**:
  $$\text{Key} = \text{payment:3ds:events:\{transactionId\}}$$
* **Evaluación Atómica**: El script [`converge.lua`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-orchestrator/src/main/resources/scripts/converge.lua) se ejecuta de forma atómica y mono-hilo en el shard correspondiente de Redis. Comprueba y añade los tokens dentro del conjunto (*Set*) en tiempo constante $O(1)$, impidiendo cualquier race condition de lectura/escritura concurrente.

---

### 🗝️ Caffeine Cache (`refreshAfterWrite`) & Dynamic Vault
* **Problema**: Consultar HashiCorp Vault de forma síncrona en cada webhook entrante para obtener la clave HMAC-SHA256 introduce una latencia de red inaceptable (50-100 ms) y sobrecarga la API de Vault.
* **Solución**: [`MerchantProfileCache.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-common/src/main/java/com/zyrconpay/aegisgate/common/cache/MerchantProfileCache.java) implementa un `LoadingCache` de Caffeine configurado con:
  * `expireAfterWrite(300, TimeUnit.SECONDS)`
  * `refreshAfterWrite(240, TimeUnit.SECONDS)`
* **Impacto**: Cuando una clave expira a los 240s, Caffeine responde inmediatamente con el valor cacheado actual al hilo de la petición HTTP y dispara la recarga desde Vault en un hilo de fondo asincrónico. La validación en el Ingress no sufre penalización de latencia.

---

## 3. 📊 Diagramas de Arquitectura Mermaid

### 📐 Diagrama de Arquitectura Lógica Multimódulo

```mermaid
graph TB
    subgraph CommonModule ["aegisgate-common"]
        Records["Java 21 Records<br/>- PaymentIntentEvent<br/>- WebhookReceivedEvent<br/>- VerificationEventSet"]
        VaultSvc["VaultMerchantCredentialService<br/>(Spring Vault API)"]
        CaffeineCache["MerchantProfileCache<br/>(refreshAfterWrite 240s)"]
        Exceptions["AegisGateExceptions<br/>- SecurityBypassException"]
    end

    subgraph IngressModule ["aegisgate-ingress (Stateless DMZ - Port 8081)"]
        IntentCtrl["PaymentIntentController<br/>(POST /api/v1/payments/intents)"]
        WebhookCtrl["WebhookController<br/>(POST /api/v1/gateways/payway/webhooks)"]
        SigVal["SignatureValidationService<br/>(HMAC-SHA256 Constant-Time)"]
        KafkaPub["KafkaEventPublisher<br/>(EventPublisher Interface)"]
    end

    subgraph OrchestratorModule ["aegisgate-orchestrator (Core Engine - Port 8082)"]
        KafkaSub["TransactionEventConsumer<br/>(@KafkaListener Virtual Threads)"]
        RedisSvc["RedisStateService<br/>(EVAL converge.lua Engine)"]
        TxRegistry["TransactionStateRegistry<br/>(Sinks.Many Broadcast for SSE)"]
        QueryCtrl["TransactionQueryController<br/>(GET /status | GET /status-stream | POST /authorize)"]
    end

    subgraph Infrastructure ["Backing Services"]
        Vault[("HashiCorp Vault<br/>/secret/data/merchants/*")]
        KafkaCluster[("Apache Kafka KRaft<br/>Topics: payment-intents, payment-webhooks")]
        RedisCluster[("Redis 7 Cluster<br/>Set: payment:3ds:events:{txId}")]
    end

    IntentCtrl --> KafkaPub
    WebhookCtrl --> SigVal
    WebhookCtrl --> CaffeineCache
    CaffeineCache --> VaultSvc
    VaultSvc --> Vault
    SigVal --> WebhookCtrl
    WebhookCtrl --> KafkaPub
    KafkaPub --> KafkaCluster
    KafkaCluster --> KafkaSub
    KafkaSub --> RedisSvc
    RedisSvc --> RedisCluster
    RedisSvc --> TxRegistry
    TxRegistry --> QueryCtrl
```

---

### ⏱️ Diagrama de Secuencia de Datos (Happy Path & Late Binding)

```mermaid
sequenceDiagram
    autonumber
    actor Client as Frontend / Checkout Client
    participant Ingress as AegisGate Ingress (WebFlux)
    participant Vault as Vault / Caffeine Cache
    participant Kafka as Apache Kafka (KRaft)
    participant Orch as State Orchestrator (Virtual Threads)
    participant Redis as Redis Cluster Shard
    actor Payway as Payway Gateway / Bank ACS

    Note over Client, Ingress: 1. Inicio de Intención de Pago (Checkout Intent)
    Client->>Ingress: POST /api/v1/payments/intents (X-Merchant-ID)
    Ingress->>Kafka: Publish PAYMENT_INTENT_CREATED to 'payment-intents'
    Ingress-->>Client: HTTP 202 Accepted (<10ms)
    Kafka->>Orch: Dispatch event to Virtual Thread Consumer
    Orch->>Redis: EVAL converge.lua (SADD payment:3ds:events:{txId} PAYMENT_INTENT_CREATED)
    Redis-->>Orch: Return 0 (PENDING)

    Note over Payway, Ingress: 2. Desafío 3DS y Callback Asincrónico de Webhook
    Payway->>Ingress: POST /api/v1/gateways/payway/webhooks (X-Payway-Signature)
    Ingress->>Vault: getCredentials(merchantId)
    Vault-->>Ingress: Secret HMAC Signing Key (cached)
    Note over Ingress: Compute HMAC-SHA256 & MessageDigest.isEqual()
    alt Firma Válida
        Ingress->>Kafka: Publish 3DS_WEBHOOK_RECEIVED:SUCCESS to 'payment-webhooks'
        Ingress-->>Payway: HTTP 202 Accepted
    else Firma Inválida
        Ingress-->>Payway: HTTP 401 Unauthorized
    end

    Kafka->>Orch: Dispatch event to Virtual Thread Consumer
    Orch->>Redis: EVAL converge.lua (SADD payment:3ds:events:{txId} 3DS_WEBHOOK_RECEIVED:SUCCESS)
    Redis-->>Orch: Return 1 (CONVERGED_VERIFIED)
    Orch-->>Client: Real-Time SSE Event Stream Update (status: CONVERGED_VERIFIED)

    Note over Client, Orch: 3. Captura / Autorización Atómica de Fondos
    Client->>Orch: POST /api/v1/payments/{txId}/authorize
    Orch->>Redis: EVAL converge.lua ARGV: AUTHORIZE_CHECK
    alt Tokens Válidos en el Set
        Redis->>Redis: DEL key payment:3ds:events:{txId}
        Redis-->>Orch: Return 1 (Tokens Consumed)
        Orch-->>Client: HTTP 200 OK (status: AUTHORIZED)
    else Token Faltante / Bypass Intent
        Redis-->>Orch: Return -1
        Orch-->>Client: HTTP 403 Forbidden (SecurityBypassException)
    end
```

---

### 🌐 Diagrama de Topología Física y Seguridad de Red (DMZ vs Private Subnet)

```mermaid
graph TD
    subgraph InternetZone ["Zona Externa / Internet Publica"]
        Browser["Navegador Cliente / Mobile App"]
        PaywayACS["Servidores Payway / Banco Emisor (ACS)"]
    end

    subgraph PublicDMZ ["Zona Desmilitarizada (Public DMZ)"]
        WAF["Web Application Firewall / Load Balancer"]
        IngressNode["AegisGate Ingress Gateway (WebFlux)<br/>- IP Pública / Puerto 8081<br/>- Sin drivers JDBC/JPA<br/>- Sin estado en disco/memoria<br/>- Validación HMAC en memoria"]
    end

    subgraph PrivateSubnet ["Subred Privada Interna (Isolated Private Subnet)"]
        KafkaNodes[("Cluster Kafka KRaft<br/>- Puertos 9092 / 9093<br/>- Acceso restringido por Security Groups")]
        OrchNodes["AegisGate State Orchestrator<br/>- Puerto 8082<br/>- Java 21 Virtual Threads<br/>- Conector Lettuce Redis"]
        RedisClusterNodes[("Redis 7 Cluster<br/>- Puerto 6379 / 16379<br/>- Hashtags {txId}<br/>- Scripting Lua")]
        VaultServer[("HashiCorp Vault<br/>- Puerto 8200<br/>- Cifrado Transit / KV Secrets Engine")]
    end

    Browser -->|HTTPS / API Intent| WAF
    PaywayACS -->|HTTPS / Webhook Callback| WAF
    WAF --> IngressNode
    IngressNode -->|Kafka Producer API| KafkaNodes
    IngressNode -->|HTTPS Key Read| VaultServer
    KafkaNodes -->|Kafka Consumer API| OrchNodes
    OrchNodes -->|RESP Protocol / EVAL| RedisClusterNodes
```

---

## 4. ⚡ Atributos de Calidad y Rendimiento (NFRs)

| Atributo de Calidad | Objetivo Técnico (Metric) | Estrategia de Arquitectura |
| :--- | :--- | :--- |
| **Latencia Ingress** | $P_{99} < 10\text{ ms}$ | Programación reactiva WebFlux + Caffeine Cache en memoria. |
| **Throughput Orquestación** | $> 50,000\text{ req/sec}$ | Java 21 Virtual Threads (Project Loom) sin bloqueo de SO. |
| **Disponibilidad** | $99.99\text{\% Uptime}$ | Ingress Stateless escalable horizontalmente en DMZ. |
| **Consistencia Atómica** | Zero Race Conditions | Script `converge.lua` ejecutado mono-hilo en shard de Redis. |
| **Aislamiento PCI-DSS** | Zero PAN Footprint | AegisGate no almacena datos de tarjeta (PAN/CVV). |

---

<p align="center">
  <b>AegisGate Architecture Manual</b> — Documentación oficial de arquitectura de software para ZyrconPay.
</p>
