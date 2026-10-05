# Seguridad: Worker, claves y verificación de compras

La app no lleva claves de Google ni de DeepSeek. Esas claves viven en el
Worker de Cloudflare. La compra `premium_unlock` la confirma Play Billing y,
cuando subes la cuenta de servicio, también el Worker con la API de Google Play.

Hasta que exista el secreto `PLAY_SERVICE_ACCOUNT_JSON`, `POST /verify-purchase`
responde `play_verifier_not_configured` y la app sigue desbloqueando solo con
Play Billing. No hace falta la cuenta de servicio para traducir.

## 1. Desplegar el Worker

Desde la carpeta `worker/`:

```bash
npm install
npx wrangler deploy
```

El límite por IP y por día UTC vuelve a estar activo. Si no configuras nada,
el valor es **200** peticiones. Para cambiarlo:

```bash
npx wrangler secret put RATE_LIMIT_PER_DAY
```

Escribe un entero positivo (por ejemplo `500`). También puedes ponerlo como
variable en `wrangler.toml` (`[vars]`, `RATE_LIMIT_PER_DAY = "200"`).

## 2. Secretos de traducción

```bash
npx wrangler secret put GOOGLE_API_KEY
npx wrangler secret put DEEPSEEK_API_KEY
```

No los pongas en `local.properties`, en Gradle ni en el APK.

En Google Cloud, restringe `GOOGLE_API_KEY` a la **Cloud Translation API**
(restricción de API). Si puedes, limita también por aplicación o por las IPs
de Cloudflare, pero la restricción de API es la que evita que esa clave pague
otros productos de Google.

## 3. Avisos de gasto

- **Google Cloud:** Facturación → Presupuestos y alertas. Crea un presupuesto
  del proyecto de Translation API y activa avisos por correo (por ejemplo al
  50 %, 90 % y 100 %).
- **DeepSeek:** en el panel de la cuenta, pon un límite de gasto mensual. El
  límite del Worker recorta abuso por IP; el límite de DeepSeek recorta la
  factura si muchas IPs distintas llaman al proxy.

## 4. Cuenta de servicio para verificar compras

Esto es lo que hace falta para que un reembolso quite el premium aunque
alguien edite las preferencias del teléfono.

1. En [Google Cloud Console](https://console.cloud.google.com/), elige el
   proyecto que usará la API y activa **Google Play Android Developer API**
   (`androidpublisher.googleapis.com`).
2. IAM y administración → Cuentas de servicio → Crear cuenta. No hace falta
   darle un rol amplio del proyecto.
3. En esa cuenta, Claves → Agregar clave → JSON. Guarda el archivo fuera del
   repositorio. No lo subas a git.
4. En [Play Console](https://play.google.com/console) → Usuarios y permisos →
   Invitar usuario. El correo es el `client_email` del JSON
   (`...@...iam.gserviceaccount.com`).
5. Concede acceso a la app `com.arnold.voicetranslator` y el permiso de
   **datos financieros** (ver pedidos e información de compras). La API
   `purchases.products.get` lo necesita. Los permisos de cuenta pueden tardar
   unas horas en aplicarse.
6. Sube el JSON completo como secreto del Worker. Puede ir en una sola línea:

   ```bash
   npx wrangler secret put PLAY_SERVICE_ACCOUNT_JSON
   ```

   Pega el contenido del archivo y confirma. Vuelve a desplegar solo si
   cambiaste código; un secreto nuevo lo toma el Worker que ya está publicado.

La ruta acepta `{ "packageName", "productId", "purchaseToken" }`. Responde
`owned: true` solo si el paquete es `com.arnold.voicetranslator`, el producto
es `premium_unlock` y Google devuelve `purchaseState == 0`. El resultado se
guarda unos 10 minutos en KV, con una clave que es el hash del token, no el
token en claro.

La app, después de un `PURCHASED` de Play, llama a esa ruta y marca premium
solo si el servidor confirma. Guarda la hora de la verificación y la repite
al abrir la app con red. Si el servidor dice que ya no es tuya (reembolso o
revocación), quita el premium. Sin red, conserva el último estado confirmado
durante 7 días. Una marca local que el servidor nunca confirmó no abre el
curso cuando la verificación ya está activa.

Las compilaciones de prueba (`com.arnold.voicetranslator.debug`) siguen
teniendo el desbloqueo de desarrollo. Ese interruptor no va en el APK de
release.
