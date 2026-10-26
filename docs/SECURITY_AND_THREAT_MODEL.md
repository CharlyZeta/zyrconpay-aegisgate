# 🛡️ AegisGate Security & Threat Model

[![Security Baseline](https://img.shields.io/badge/Security-STRIDE%20%2F%20OWASP-red.svg?style=for-the-badge&logo=shield)](https://owasp.org/)
[![ArchUnit Isolation](https://img.shields.io/badge/ArchUnit-Boundary%20Enforced-informational.svg?style=for-the-badge)](https://www.archunit.org/)
[![PCI-DSS Scope](https://img.shields.io/badge/PCI--DSS-Out%20of%20Scope%20Isolated-success.svg?style=for-the-badge)](https://www.pcisecuritystandards.org/)

Este manual especifica el **Modelo de Amenazas (STRIDE / OWASP)**, las salvaguardas criptográficas, la mitigación atómica de ataques de bypass y las reglas de aislamiento estructural implementadas en **AegisGate 3DS Verification Engine**.

---

## 📋 Tabla de Contenidos
1. [🎯 Alcance de Seguridad y Reducción PCI-DSS](#1-alcance-de-seguridad-y-reducción-pci-dss)
2. [⚔️ Modelo de Amenazas STRIDE / OWASP](#2-modelo-de-amenazas-stride--owasp)
3. [🔒 Mitigación Atómica de Ataques de Bypass (Dual Tokens en Redis)](#3-mitigación-atómica-de-ataques-de-bypass-dual-tokens-en-redis)
4. [🔑 Verificación Criptográfica HMAC-SHA256 y Protección Timing Attack](#4-verificación-criptográfica-hmac-sha256-y-protección-timing-attack)
5. [🔐 Integración con HashiCorp Vault y Rotación Sin Downtime](#5-integración-con-hashicorp-vault-y-rotación-sin-downtime)
6. [📐 Reglas de Aislamiento Estructural con ArchUnit](#6-reglas-de-aislamiento-estructural-con-archunit)

---

## 1. 🎯 Alcance de Seguridad y Reducción PCI-DSS

El estándar **PCI-DSS (Payment Card Industry Data Security Standard)** aplica rigurosamente a cualquier sistema que almacene, procese o transmita datos del titular de la tarjeta (PAN, CVV, fecha de expiración).

> [!IMPORTANT]
> **Reducción del Alcance de Auditoría PCI-DSS (Out-of-Scope Design)**:
> * **Zero PAN Storage**: AegisGate **jamás procesa ni almacena números de tarjeta de crédito/débito (PAN)**. Todas las peticiones hacen referencia exclusivamente a un UUID de transacción (`transactionId`).
> * **Separación Física de Ingress**: El módulo `aegisgate-ingress` opera de manera reactiva y stateless en la DMZ sin conexiones a bases de datos relacionales o almacenes de persistencia de largo plazo.
> * **Imposibilidad de Exfiltración**: Al no existir datos PCI dentro de la memoria de los microservicios ni en Redis/Kafka, una intrusión no compromete información financiera sensible de tarjetahabientes.

---

## 2. ⚔️ Modelo de Amenazas STRIDE / OWASP

AegisGate evalúa y mitiga formalmente las siguientes amenazas basadas en la metodología **STRIDE**:

| Categoria STRIDE | Amenaza en Procesamiento 3DS | Impacto Potencial | Mecanismo de Mitigación en AegisGate |
| :--- | :--- | :--- | :--- |
| **Spoofing** *(Suplantación)* | Un atacante envía un webhook falso simulando ser el adquirente Payway. | Ejecución de órdenes no pagadas. | Validacion estricta de firma **HMAC-SHA256** en cabecera `X-Payway-Signature` utilizando claves secretas únicas por comercio obtenidas dinámicamente desde HashiCorp Vault. |
| **Tampering** *(Manipulación)* | Alteración del payload del webhook o cambio de montos en tránsito. | Alteración de estado o discrepancia de valores. | Cálculo hash HMAC sobre el cuerpo exacto `rawBody` sin deserializar. Si un solo carácter cambia, la firma no coincide y retorna `HTTP 401 Unauthorized`. |
| **Repudiation** *(Repudio)* | El comercio o el adquirente niega haber emitido o recibido una transacción. | Disputas financieras / Chargebacks. | Event Sourcing inmutable en Apache Kafka con timestamping unificado. Registro auditable de eventos `PaymentIntentEvent` y `WebhookReceivedEvent`. |
| **Information Disclosure** | Intercepción de claves secretas de comercios o tokens de autorización. | Vulneración multi-tenant. | Gestión de secretos dinámicos cifrados mediante HashiCorp Vault KV Secrets Engine v2. Caché Caffeine efímera en memoria RAM sin persistencia en disco. |
| **Denial of Service (DoS)** | Picos masivos de peticiones o inundación de webhooks maliciosos. | Caída del motor de checkout. | Capa de Ingress no bloqueante con Spring WebFlux (Netty). Procesamiento asincrónico por **Virtual Threads** de Java 21 en el Orchestrator. |
| **Elevation of Privilege** | Inyección de llamadas directas al endpoint `/authorize` omitiendo el banco. | Bypass de autenticación 3DS (Fraude). | Evaluación atómica en script Redis [`converge.lua`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-orchestrator/src/main/resources/scripts/converge.lua). Exige presencia simultánea de tokens duales. |

---

## 3. 🔒 Mitigación Atómica de Ataques de Bypass (Dual Tokens en Redis)

El vector de ataque más crítico en integraciones 3DS monolíticas ocurre cuando un cliente malicioso intercepta la comunicación del navegador e invoca directamente la URL de confirmación de orden (`/authorize`) sin haber completado el desafío biométrico o OTP en el banco emisor.

### Algoritmo de Convergencia Atómica (`converge.lua`)

AegisGate resuelve esto exigiendo la convergencia de un **Set de Tokens Duales** en Redis bajo la llave `payment:3ds:events:{transactionId}`:

```
                  +----------------------------------------------+
                  |  Set Redis: payment:3ds:events:{txId}       |
                  +----------------------------------------------+
                                         |
            +----------------------------+----------------------------+
            |                                                         |
            v Token 1                                                 v Token 2
┌───────────────────────────────┐                         ┌───────────────────────────────┐
│     PAYMENT_INTENT_CREATED    │                         │  3DS_WEBHOOK_RECEIVED:SUCCESS │
│ (Registrado por el Comercio)  │                         │ (Enviado por Pasarela Payway) │
└───────────────────────────────┘                         └───────────────────────────────┘
```

> [!SECURITY]
> **Estados del Script Lua Atómico**:
> 1. **Falta Token 1 o Token 2**: El script retorna `-1`. El Orchestrator lanza una excepción [`SecurityBypassException`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-common/src/main/java/com/zyrconpay/aegisgate/common/exception/SecurityBypassException.java) y responde `HTTP 403 Forbidden`.
> 2. **Token Webhook reporta `3DS_WEBHOOK_RECEIVED:FAILED`**: El script retorna `-2`. Se rechaza la autorización (`HTTP 403 Forbidden`).
> 3. **Ambos Tokens Presentes (`PAYMENT_INTENT_CREATED` + `3DS_WEBHOOK_RECEIVED:SUCCESS`)**: El script elimina atómicamente la llave de Redis (`DEL`), consume el token para evitar ataques de réplica (*Replay Attacks*) y retorna `1` (`HTTP 200 OK - AUTHORIZED`).

```mermaid
graph TD
    A[Petición POST /api/v1/payments/{txId}/authorize] --> B[EVAL converge.lua ARGV: AUTHORIZE_CHECK]
    B --> C{¿Están los dos tokens en el Set?}
    C -- "Sí (Intent + Webhook Success)" --> D[Eliminar Key de Redis atómicamente <br/> Retornar 1 -> HTTP 200 OK AUTHORIZED]
    C -- "No (Falta alguna verificación)" --> E[Retornar -1 -> Lanzar SecurityBypassException <br/> HTTP 403 Forbidden]
    C -- "Webhook reportó FAILED" --> F[Retornar -2 -> Rechazar Transacción <br/> HTTP 403 Forbidden]
```

---

## 4. 🔑 Verificación Criptográfica HMAC-SHA256 y Protección Timing Attack

En [`SignatureValidationService.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-ingress/src/main/java/com/zyrconpay/aegisgate/ingress/service/SignatureValidationService.java), la autenticidad de cada webhook entrante se calcula en memoria antes de aceptar cualquier procesamiento.

### Mitigación de Side-Channel Timing Attacks

Una vulnerabilidad común al comparar firmas criptográficas en Java es utilizar `String.equals()`, la cual evalúa carácter por carácter y retorna tempranamente ante el primer descalce. Un atacante midiendo latencias de microsegundos puede deducir la firma válida posición por posición.

AegisGate mitiga este vector mediante **comparación en tiempo constante**:

```java
// Implementación en SignatureValidationService.java
byte[] computedHashBytes = sha256Hmac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
String computedHash = HexFormat.of().formatHex(computedHashBytes);

// MessageDigest.isEqual realiza una comparación en tiempo constante (constant-time)
return MessageDigest.isEqual(
        computedHash.getBytes(StandardCharsets.UTF_8),
        headerSignature.getBytes(StandardCharsets.UTF_8)
);
```

---

## 5. 🔐 Integración con HashiCorp Vault y Rotación Sin Downtime

AegisGate elimina por completo las claves secretas hardcodeadas en archivos de propiedades o variables de entorno del sistema.

### Flujo de Resolución Multi-Tenant de Secretos

1. Cada comercio posee su clave de firma HMAC en la ruta `/secret/data/merchants/{merchantId}` de HashiCorp Vault.
2. [`VaultMerchantCredentialService.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-common/src/main/java/com/zyrconpay/aegisgate/common/service/VaultMerchantCredentialService.java) realiza la consulta de forma segura.
3. [`MerchantProfileCache.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/aegisgate-common/src/main/java/com/zyrconpay/aegisgate/common/cache/MerchantProfileCache.java) gestiona las claves en un `LoadingCache` de Caffeine:
   - **`refreshAfterWrite(240s)`**: Permite rotar la secret key en Vault y que AegisGate la refresque asincrónicamente en segundo plano sin interrumpir el flujo de validación de webhooks activos.

---

## 6. 📐 Reglas de Aislamiento Estructural con ArchUnit

La suite de pruebas estructurales [`AegisGateBoundaryTest.java`](file:///e:/Propios/ZyrconPay/zyrconpay-aegisgate/tests/src/test/java/com/zyrconpay/aegisgate/structural/AegisGateBoundaryTest.java) aplica pruebas de arquitectura que fallan el build si algún desarrollador viola las fronteras de seguridad:

```java
public class AegisGateBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.zyrconpay.aegisgate");

    @Test
    public void testModuleBoundaries() {
        // Ingress no puede importar ni depender de clases del orchestrator
        ArchRule boundaryRule = noClasses()
                .that().resideInAPackage("..ingress..")
                .should().dependOnClassesThat().resideInAPackage("..orchestrator..");
        boundaryRule.check(classes);
    }

    @Test
    public void testDirectPaymentIsolation() {
        // Paquetes de pago directo tienen prohibido acceder al motor de orquestación 3DS
        ArchRule isolationRule = noClasses()
                .that().resideInAPackage("..directpayment..")
                .should().dependOnClassesThat().resideInAPackage("..orchestrator..");
        isolationRule.allowEmptyShould(true).check(classes);
    }

    @Test
    public void testIngressDatabaseIsolation() {
        // Ingress no puede cargar dependencias de bases de datos relacionales (JPA / Hibernate / JDBC)
        ArchRule noDatabaseRule = noClasses()
                .that().resideInAPackage("..ingress..")
                .should().dependOnClassesThat().resideInAPackage("..jpa..")
                .orShould().dependOnClassesThat().resideInAPackage("..jdbc..")
                .orShould().dependOnClassesThat().resideInAPackage("..hibernate..");
        noDatabaseRule.check(classes);
    }
}
```

---

<p align="center">
  <b>AegisGate Security Manual</b> — Especificaciones de seguridad y modelo de amenazas para ZyrconPay.
</p>
