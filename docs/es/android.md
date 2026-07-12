# Herramientas de Android

Agora puede acceder de forma segura a ciertas funciones del sistema Android cuando el modelo las necesita. Estas herramientas permiten al modelo obtener tu ubicación actual, leer contactos o interactuar con tu calendario, respetando el sistema de permisos de Android.

## Herramientas disponibles

| Herramienta | Finalidad |

| ------------ | ----------------------------------------------------- |

| **Ubicación** | Obtener la ubicación aproximada o precisa del dispositivo |

| **Contactos** | Buscar y leer los contactos almacenados en el dispositivo |

| **Calendario** | Leer los próximos eventos y crear nuevas entradas en el calendario |

El modelo detecta automáticamente las herramientas habilitadas y decide cuándo son útiles durante una conversación.

## Privacidad y permisos

Las herramientas de Android requieren permisos estándar de ejecución de Android.

La primera vez que el modelo intenta usar una de estas herramientas, Agora solicitará el permiso de Android correspondiente. Los permisos solo se solicitan cuando se necesita una herramienta por primera vez.

¡¡¡Nota!!!

Puedes revocar los permisos en cualquier momento desde la configuración de Android de tu dispositivo.

## Configuración

1. Ve a **Ajustes → Android**
2. Habilita las herramientas que quieras que use el modelo:

- **Ubicación**

- **Contactos**

- **Calendario**

3. Concede los permisos de Android solicitados cuando se te pida.

Una vez habilitadas, el modelo podrá acceder a estas herramientas automáticamente cuando sean útiles durante una conversación.

## Ubicación

La herramienta Ubicación permite al modelo determinar la ubicación actual de tu dispositivo.

Usos típicos:

- Encontrar lugares cercanos
- Proporcionar información meteorológica local
- Recomendaciones basadas en la ubicación
- Estimar tiempos de viaje
- Responder preguntas sobre tu zona actual

Dependiendo de la configuración de tu dispositivo y los permisos otorgados, la ubicación puede ser aproximada o precisa.

## Contactos

La herramienta Contactos permite al modelo buscar los contactos almacenados en tu dispositivo.

Usos típicos:

- Buscar números de teléfono
- Encontrar direcciones de correo electrónico
- Identificar contactos guardados
- Seleccionar contactos para tareas de mensajería o comunicación

El modelo solo accede a la información de contacto necesaria para atender su solicitud.

## Calendario

La herramienta Calendario permite al modelo leer su calendario y crear eventos.

Usos típicos:

- Consultar su agenda
- Ver los próximos eventos
- Encontrar disponibilidad horaria
- Crear citas
- Revisar los detalles de las reuniones

Crear o modificar eventos requiere permiso de escritura en el calendario.

## Seguridad

Las herramientas de Android utilizan el sistema de permisos integrado de Android.

- Se solicita permiso en tiempo de ejecución antes del primer uso.
- Los permisos se pueden revocar en cualquier momento.
- No se puede acceder a las herramientas deshabilitadas.
- Todo el acceso se realiza localmente a través del marco de permisos de Android.

Agora no puede acceder a datos protegidos sin su permiso.

## Solución de problemas

### Permiso denegado

Si el modelo indica que no puede acceder a una herramienta:

- Verifica que la herramienta esté habilitada en **Ajustes → Android**

- Confirma que se haya otorgado el permiso de Android necesario.
- Si es necesario, revoca y vuelve a otorgar el permiso en los Ajustes de Android.

### Ubicación no disponible

- Asegúrate de que los Servicios de ubicación estén activados en tu dispositivo.
- Muévete a una zona con mejor cobertura GPS o de red.
- Otorga una ubicación precisa si se requiere mayor exactitud.

### Calendario o contactos vacíos

Verifica que tu dispositivo contenga eventos de calendario o contactos y que se haya otorgado el permiso de Android correspondiente.
