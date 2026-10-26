# 🔌 AegisGate API Integration Guide

[![OpenAPI 3.0](https://img.shields.io/badge/OpenAPI-3.0-green.svg?style=for-the-badge&logo=openapi-initiative)](http://localhost:8081/swagger-ui.html)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.x-brightgreen.svg?style=for-the-badge&logo=springboot)](https://spring.io/)
[![SSE Realtime](https://img.shields.io/badge/Realtime-Server--Sent%20Events-blue.svg?style=for-the-badge)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

Guía completa de integración paso a paso para **comerciantes (Merchants)**, **plataformas e-commerce** y **pasarelas adquirentes (Payway Gateway)** con **AegisGate 3DS Verification Engine**.

---

## 📋 Tabla de Contenidos
1. [🚀 Visión General del Flujo de Integración](#1-visión-general-del-flujo-de-integración)
2. [📡 Referencia Completa de Endpoints](#2-referencia-completa-de-endpoints)
   - [1. Registrar Intención de Pago (`POST /api/v1/payments/intents`)](#1-registrar-intención-de-pago-post-apiv1paymentsintents)
   - [2. Recibir Webhook Asincrónico Payway (`POST /api/v1/gateways/payway/webhooks`)](#2-recibir-webhook-asincrónico-payway-post-apiv1gatewayspaywaywebhooks)
   - [3. Consultar Estado Convergido (`GET /api/v1/payments/{txId}/status`)](#3-consultar-estado-convergido-get-apiv1paymentstxidstatus)
   - [4. Transmisión SSE en Tiempo Real (`GET /api/v1/payments/{txId}/status-stream`)](#4-transmisión-sse-en-tiempo-real-get-apiv1paymentstxidstatus-stream)
   - [5. Autorizar y Consumir Tokens (`POST /api/v1/payments/{txId}/authorize`)](#5-autorizar-y-consumir-tokens-post-apiv1paymentstxidauthorize)
3. [🔐 Cálculo Cliente de Firma HMAC-SHA256](#3-cálculo-cliente-de-firma-hmac-sha256)
   - [Ejemplo en Node.js / TypeScript](#ejemplo-en-nodejs--typescript)
   - [Ejemplo en Python](#ejemplo-en-python)
   - [Ejemplo en Java](#ejemplo-en-java)
   - [Ejemplo en React (Web Crypto API)](#ejemplo-en-react-web-crypto-api)
4. [📊 Matriz de Códigos HTTP y Manejo de Excepciones](#4-matriz-de-códigos-http-y-manejo-de-excepciones)

---

## 1. 🚀 Visión General del Flujo de Integración

La integración con AegisGate consta de **tres pasos principales**:

```
+-------------------+           +-----------------------+           +----------------------+
|  1. REGISTRO      |           |  2. CALLBACK 3DS      |           |  3. CAPTURA Y COBRO  |
|  El frontend del  |           |  Payway envía webhook |           |  El backend valida   |
|  comercio envía   | ------->  |  firmado HMAC con la  | ------->  |  convergencia y      |
|  el Intent a      |           |  respuesta del banco  |           |  ejecuta /authorize  |
|  Ingress (8081).  |           |  emisor (8081).       |           |  en Orchestrator     |
+-------------------+           +-----------------------+           |  (8082).             |
                                                                    +----------------------+
```

---

## 2. 📡 Referencia Completa de Endpoints

### 1. Registrar Intención de Pago (`POST /api/v1/payments/intents`)

* **Servicio**: `aegisgate-ingress` (Puerto 8081)
* **Descripción**: Registra el checkout intent del usuario al iniciar el proceso de pago. Encola el evento `PAYMENT_INTENT_CREATED` en Kafka.

#### Cabeceras HTTP
| Cabecera | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- | :--- |
| `Content-Type` | String | Sí | Tipo de contenido | `application/json` |
| `X-Merchant-ID` | String | Opcional | ID del comercio (Default: `default-merchant`) | `merchant-alpha` |

#### Payload de Entrada (JSON)
```json
{
  "transaction_id": "tx-flow-happy-999",
  "amount": 1500.00,
  "currency": "ARS"
}
```

#### Respuesta Esperada (`HTTP 202 Accepted`)
```http
HTTP/1.1 202 Accepted
Content-Length: 0
```

#### Ejemplo `curl`
```bash
curl -X POST http://localhost:8081/api/v1/payments/intents \
  -H "Content-Type: application/json" \
  -H "X-Merchant-ID: merchant-alpha" \
  -d '{
    "transaction_id": "tx-flow-happy-999",
    "amount": 1500.00,
    "currency": "ARS"
  }'
```

---

### 2. Recibir Webhook Asincrónico Payway (`POST /api/v1/gateways/payway/webhooks`)

* **Servicio**: `aegisgate-ingress` (Puerto 8081)
* **Descripción**: Recibe la notificación asincrónica del adquirente Payway tras el desafío 3DS. Valida la firma HMAC-SHA256 contra la clave cargada en Vault.

#### Cabeceras HTTP
| Cabecera | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- | :--- |
| `Content-Type` | String | Sí | Tipo de contenido | `application/json` |
| `X-Merchant-ID` | String | Sí | ID del comercio registrado en Vault | `merchant-alpha` |
| `X-Payway-Signature` | String | Sí | Hexadecimal de la firma HMAC-SHA256 | `a5940428d085954a...` |

#### Payload de Entrada (JSON)
```json
{
  "transaction_id": "tx-flow-happy-999",
  "status": "approved",
  "verification": {
    "status_3ds": "SUCCESS",
    "eci": "05"
  }
}
```

#### Respuestas HTTP Posibles
* `HTTP 202 Accepted`: Firma válida y webhook encolado en Kafka.
* `HTTP 401 Unauthorized`: Firma HMAC inválida o comercio no registrado en Vault.
* `HTTP 400 Bad Request`: Payload JSON malformado o campos requeridos ausentes.

#### Ejemplo `curl`
```bash
curl -X POST http://localhost:8081/api/v1/gateways/payway/webhooks \
  -H "Content-Type: application/json" \
  -H "X-Merchant-ID: merchant-alpha" \
  -H "X-Payway-Signature: valid-alpha-signature" \
  -d '{
    "transaction_id": "tx-flow-happy-999",
    "status": "approved",
    "verification": {
      "status_3ds": "SUCCESS",
      "eci": "05"
    }
  }'
```

---

### 3. Consultar Estado Convergido (`GET /api/v1/payments/{txId}/status`)

* **Servicio**: `aegisgate-orchestrator` (Puerto 8082)
* **Descripción**: Permite verificar en tiempo real el estado de convergencia de una transacción en Redis.

#### Parámetros de Ruta
| Parámetro | Tipo | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- |
| `txId` | String | Identificador único de la transacción | `tx-flow-happy-999` |

#### Respuesta Esperada (`HTTP 200 OK`)
```json
{
  "transactionId": "tx-flow-happy-999",
  "hasPaymentIntent": true,
  "hasWebhookReceived": true,
  "status": "CONVERGED_VERIFIED"
}
```

#### Ejemplo `curl`
```bash
curl http://localhost:8082/api/v1/payments/tx-flow-happy-999/status
```

---

### 4. Transmisión SSE en Tiempo Real (`GET /api/v1/payments/{txId}/status-stream`)

* **Servicio**: `aegisgate-orchestrator` (Puerto 8082)
* **Descripción**: Abre una conexión reactiva Server-Sent Events (SSE) para emitir cambios de estado en tiempo real hacia aplicaciones SPA / Mobile sin necesidad de polling.

#### Cabeceras de Respuesta
```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
```

#### Estructura del Evento SSE
```event-stream
data:{"transactionId":"tx-flow-happy-999","hasPaymentIntent":true,"hasWebhookReceived":true,"status":"CONVERGED_VERIFIED"}
```

#### Ejemplo `curl`
```bash
curl -N http://localhost:8082/api/v1/payments/tx-flow-happy-999/status-stream
```

---

### 5. Autorizar y Consumir Tokens (`POST /api/v1/payments/{txId}/authorize`)

* **Servicio**: `aegisgate-orchestrator` (Puerto 8082)
* **Descripción**: Punto de control de captura/autorización final. Exige estado `CONVERGED_VERIFIED` y consume atómicamente la llave de Redis para prevenir ataques de bypass y reingreso de transacciones.

#### Respuesta Esperada Exitosos (`HTTP 200 OK`)
```json
{
  "status": "AUTHORIZED",
  "message": "Transaction successfully verified and token consumed."
}
```

#### Respuesta Ante Intento de Bypass (`HTTP 403 Forbidden`)
```json
{
  "errorCode": "SECURITY_BYPASS_ATTEMPT",
  "message": "Security bypass detected: missing required verification tokens for transaction tx-hacker-007"
}
```

#### Ejemplo `curl`
```bash
curl -X POST http://localhost:8082/api/v1/payments/tx-flow-happy-999/authorize
```

---

## 3. 🔐 Cálculo Cliente de Firma HMAC-SHA256

Para simular o integrar la emisión de webhooks desde el adquirente Payway, el cuerpo exacto en formato raw string debe ser firmado con el algoritmo **HMAC-SHA256** utilizando la `signingKey` del comercio.

### Ejemplo en Node.js / TypeScript
```typescript
import crypto from 'crypto';

function computeHmacSignature(rawBody: string, secretKey: string): string {
    return crypto
        .createHmac('sha256', secretKey)
        .update(rawBody, 'utf8')
        .digest('hex');
}

const rawBody = JSON.stringify({
    transaction_id: "tx-flow-happy-999",
    status: "approved",
    verification: { status_3ds: "SUCCESS", eci: "05" }
});

const signature = computeHmacSignature(rawBody, "secret-alpha-key");
console.log("X-Payway-Signature:", signature);
```

---

### Ejemplo en Python
```python
import hmac
import hashlib

def compute_hmac_signature(raw_body: str, secret_key: str) -> str:
    return hmac.new(
        secret_key.encode('utf-8'),
        raw_body.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()

raw_body = '{"transaction_id":"tx-flow-happy-999","status":"approved","verification":{"status_3ds":"SUCCESS","eci":"05"}}'
signature = compute_compute_hmac_signature(raw_body, "secret-alpha-key")
print("X-Payway-Signature:", signature)
```

---

### Ejemplo en Java
```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class SignatureHelper {
    public static String computeSignature(String rawBody, String secretKey) throws Exception {
        Mac sha256Hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256Hmac.init(keySpec);
        byte[] hash = sha256Hmac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
```

---

### Ejemplo en React (Web Crypto API)
```typescript
async function computeWebCryptoHmac(rawBody: string, secretKey: string): Promise<string> {
    const encoder = new TextEncoder();
    const keyData = encoder.encode(secretKey);
    const bodyData = encoder.encode(rawBody);

    const cryptoKey = await window.crypto.subtle.importKey(
        'raw', keyData, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
    );

    const signatureBuffer = await window.crypto.subtle.sign('HMAC', cryptoKey, bodyData);
    return Array.from(new Uint8Array(signatureBuffer))
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
}
```

---

## 4. 📊 Matriz de Códigos HTTP y Manejo de Excepciones

| Código HTTP | Excepción / Escenario | Descripción de Causas | Acción Recomendada |
| :--- | :--- | :--- | :--- |
| `200 OK` | `AUTHORIZED` | Tokens verificados y consumidos exitosamente. | Proceder a la entrega del producto/servicio. |
| `202 Accepted` | Processing | Intención o Webhook encolado correctamente en Kafka. | Esperar actualización vía SSE o polling status. |
| `400 Bad Request` | Malformed JSON | Payload JSON inválido o atributos requeridos ausentes. | Verificar la estructura del cuerpo de la petición. |
| `401 Unauthorized` | Invalid HMAC | La firma HMAC no coincide o la clave no existe en Vault. | Validar la clave secreta `signingKey` en Vault. |
| `403 Forbidden` | `SecurityBypassException` | Intento de autorizar sin convergencia de ambos tokens. | Redirigir al usuario al desafío 3DS en el banco. |
| `404 Not Found` | Unknown Tx | La transacción no existe o su TTL (600s) ha expirado. | Reiniciar el flujo de checkout intent. |
| `500 Internal Error` | Engine Failure | Error no controlado en la infraestructura Redis/Kafka. | Consultar telemetría y logs en Actuator. |

---

<p align="center">
  <b>AegisGate API Integration Guide</b> — Manual de integración oficial para comercios y pasarelas.
</p>
