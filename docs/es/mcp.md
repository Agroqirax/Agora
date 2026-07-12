# Servidores MCP

Agora puede conectarse a servidores [Model Context Protocol](https://modelcontextprotocol.io) y dejar que el modelo llame a las herramientas que exponen: motores de búsqueda, bases de datos, automatización del hogar, API internas de la empresa o cualquier otra cosa para la que usted o un tercero haya creado un servidor MCP.

!!! nota
    Actualmente, Agora solo admite **herramientas** de MCP. Los recursos, las indicaciones y las muestras aún no se han implementado.

## Cómo funciona

```texto
Agora (Android) ──HTTPS (transporte HTTP transmitible)──▶ Servidor MCP
                                                          │
                                                          ├── inicializar
                                                          ├── herramientas/lista
                                                          └── herramientas/llamada
```

Agora habla el transporte MCP **HTTP transmitible** (un único punto final HTTP, no stdio ni el antiguo transporte HTTP+SSE). En el primer uso, abre una sesión con `initialize`, enumera las herramientas del servidor con `tools/list` y permite que el modelo las invoque con `tools/call`. La lista de herramientas se almacena en caché durante aproximadamente 30 segundos por servidor, por lo que los mensajes repetidos no se vuelven a conectar cada vez; Si el servidor finaliza la sesión, Agora se vuelve a conectar automáticamente en la siguiente llamada.

El modelo decide cuándo usar una herramienta MCP por sí solo, de la misma manera que decide usar la búsqueda web o el shell: no hay un activador manual.

## Seguridad

Los servidores MCP que agrega son código arbitrario en el que elige confiar el acceso a la herramienta, por lo que Agora los trata con cautela de forma predeterminada:

- **Las herramientas de solo lectura se ejecutan sin preguntar.** Si un servidor marca una herramienta con `readOnlyHint`, Agora la llama automáticamente.
- **Todo lo demás pide confirmación.** Si una herramienta no está marcada como de solo lectura, Agora la trata como potencialmente destructiva (incluidas las herramientas que simplemente no declaran ninguna `sugerencia destructiva`) y muestra un cuadro de diálogo de confirmación con el nombre de la herramienta y los argumentos antes de ejecutarla.
- **"Permitir siempre este servidor"** le permite omitir el mensaje durante el resto de la sesión. Esto se reinicia cuando se reinicia Agora.
- **La autenticación se envía únicamente al servidor que configuró.** Un token de portador o un encabezado personalizado que agregue se envía únicamente a la URL de ese servidor.

!!! advertencia
    Si la URL de un servidor usa `http://` simple en lugar de `https://`, cualquier token de portador o encabezado que configure viaja sin cifrar. Prefiera puntos finales `https://`, especialmente a redes que no sean de confianza.

## Configuración

### Paso 1: Obtenga un servidor MCP

Puede ser un servidor MCP público, uno que su organización ejecute internamente o uno que usted mismo aloje. Debe exponer el transporte **HTTP transmitible** en una única URL (comúnmente termina en `/mcp`).

### Paso 2: Agréguelo en Agora

1. Vaya a **Configuración → Servidores MCP**
2. Habilite **Habilitar herramientas MCP**
3. Toque **Agregar servidor**
4. Complete los detalles del servidor:

| Campo | Descripción | Ejemplo |
| ----------------- | ----------------------------------------------------------------------------------------------- | --------------------------------- |
| **Nombre** | Nombre para mostrar para este servidor | `Asistente de hogar` |
| **Descripción** | Nota opcional sobre para qué sirve. Si se deja en blanco, se muestra el host del servidor.                 | `Controla luces y termostatos` |
| **URL del servidor** | El punto final HTTP MCP Streamable | `https://ejemplo.com/mcp` |
| **Ficha al portador** | Opcional: enviado como `Autorización: Portador <token>` | El token API de su servidor |
| **Encabezados adicionales** | Opcional: uno por línea, como `Nombre: valor`, para servidores que esperan autenticación o enrutamiento mediante un encabezado personalizado | `X-Api-Key: secreto` |
| **Tiempo de espera** | Tiempo de espera por solicitud, 5 a 120 segundos | `30` |

5. Toque **Probar conexión** para verificar que Agora pueda comunicarse con el servidor y ver cuántas herramientas expone, antes de guardar.
6. Toca **Guardar**.

Una vez que un servidor se conecta exitosamente, ya sea desde una conexión de prueba o desde un uso real en un chat, su nombre y versión (según lo informado por el servidor) aparecen como una pequeña insignia junto a su entrada en la lista de servidores.

### Paso 3: Usar

Envíe un mensaje que podría utilizar una de las herramientas del servidor. Si la herramienta no es de sólo lectura, se le pedirá que la permita la primera vez; después de eso, se recuerda por el resto de la sesión (o hasta que lo rechaces).

## Soporte multiservidor

Agregue tantos servidores como desee: una API de búsqueda, un sistema de tickets interno, un centro de automatización del hogar. Cada uno se configura y autentica de forma independiente, y sus herramientas tienen un espacio de nombres automático (por ejemplo, `mcp__home_assistant__turn_on_light`), por lo que las herramientas con nombres idénticos de diferentes servidores nunca chocan.

Al deshabilitar **Habilitar herramientas MCP**, o la propia casilla de verificación **Habilitado** de un solo servidor, se eliminan sus herramientas de lo que el modelo puede ver sin eliminar su configuración.

## Confirmación de llamadas de herramientas destructivas

Puede desactivar completamente los mensajes de confirmación con **Confirmar llamadas destructivas a herramientas MCP** en **Configuración → Servidores MCP**: las herramientas de solo lectura siempre se ejecutan sin preguntar, independientemente de esta configuración. Desactivarlo significa que todas las herramientas de cada servidor habilitado se ejecutan inmediatamente sin aviso, así que desactívelo solo para servidores en los que confíe plenamente.

## Solución de problemas

### La conexión de prueba falla

- Vuelva a verificar la **URL del servidor**: debe ser el punto final completo (por ejemplo, `.../mcp`), no solo el host.
- Si el servidor requiere autenticación, verifique que el **Token de portador** o los **Encabezados adicionales** sean correctos.
- Confirme que el servidor implementa el transporte HTTP Streamable, no stdio o el transporte HTTP+SSE heredado.
- Verifique que se pueda acceder a la URL desde su dispositivo (no solo desde la red de su escritorio)

### El modelo nunca llama a la herramienta.

- Confirme que **Habilitar herramientas MCP** y la casilla de verificación **Habilitado** del servidor específico estén activadas.
- Pruebe **Probar conexión** para confirmar que el servidor actualmente incluye esa herramienta.
- Algunos modelos son más reacios a llamar a herramientas desconocidas sin una razón clara en la conversación; intenta ser explícito sobre lo que quieres que se haga.

### Las solicitudes siguen caducando

- Aumentar el **Tiempo de espera** del servidor si sus herramientas son lentas (por ejemplo, búsquedas de larga duración o automatizaciones)
- Un servidor lento o sobrecargado se reintenta como máximo una vez cada 30 segundos en lugar de cada mensaje, por lo que una interrupción temporal no detendrá su conversación repetidamente.

### El mensaje de confirmación muestra argumentos inesperados

El cuadro de diálogo de confirmación muestra los argumentos exactos que el modelo está a punto de enviar. Si parecen incorrectos, rechace la llamada; el modelo normalmente verá la denegación y ajustará su próximo intento.