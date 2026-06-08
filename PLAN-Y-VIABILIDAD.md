# Flashback + Sinytra Connector — Diagnóstico, Plan y Viabilidad

Documento de análisis del crash de Flashback en tu pack de NeoForge + Sinytra Connector,
qué hace el mod de arreglo y, sobre todo, **qué posibilidades reales hay de dejar Flashback
completamente funcional**.

Datos de tu instancia analizados:
`G:\CurseForge\Instances\Prueba de Modpack para Servidor`

- Flashback **0.39.5** (cargado como mod Fabric vía Connector)
- Sinytra Connector **2.0.0-beta.14** + Forgified Fabric API **0.116.7**
- NeoForge **21.1.230** / Minecraft **1.21.1** / Java 21
- Create **6.0.10** + ~30 addons de Create, ~145 mods en total
- Replay analizado: `flashback/replays/2026-06-02T03_50_54.zip` (mundo "Lobby")

---

## 1. Resumen ejecutivo

El juego **no crashea al abrir el menú**, sino **al hacer click en un replay para abrirlo**.
En ese momento Flashback recrea un "mundo de replay" y carga sus registros con el cargador
de vanilla, que es **estricto**: si una sola entrada modded no se puede leer, aborta todo con

```
java.lang.IllegalStateException: Failed to load registries due to above errors
```

El arreglo (este mod) hace esa carga **tolerante solo para Flashback**: omite las entradas
que fallan y abre el replay con el resto. Es el mismo enfoque que el propio Flashback empezó
a adoptar en 0.39.1 ("carga de registros más tolerante"), pero que en tu caso no llega a
actuar porque el corte ocurre antes, dentro del código vanilla.

**¿Se puede dejar Flashback 100% funcional? Respuesta honesta:** *abrir y reproducir* replays
es muy probable (estimo ~80% con este fix). *Completamente funcional de punta a punta*
(grabar → abrir → editar → exportar vídeo) en un pack tan grande con Connector es **parcial**:
hay un posible segundo bloqueo conocido (paquetes de Create) y riesgos de render. Estimo
~50–70% sin tocar la lista de mods. Detalle abajo.

---

## 2. Diagnóstico técnico

### Ruta exacta del crash (de tu crash report del 02/06 03:51)

```
mouseClicked (lista de replays)
  -> ReplaySelectionEntry$ReplayListEntry.openReplay
    -> Flashback.openReplayWorld (Flashback.java:1148/1165)
      -> Util.blockUntilDone
        -> net.minecraft.server.WorldLoader.load
          -> net.minecraft.resources.RegistryDataLoader.load (línea 154)
             throw new IllegalStateException("Failed to load registries due to above errors")
```

`RegistryDataLoader` carga los **registros dinámicos / de datapack** (los que se leen desde
JSON: dimension_type, damage_type, biomas, encantamientos, worldgen, más los registros
dinámicos que añaden los mods). Acumula los errores de cada elemento en una lista y, si no
está vacía, **lanza la excepción y aborta**.

### Por qué falla justo aquí

Tu replay guarda una *instantánea* de los registros del servidor en el momento de grabar.
En el `metadata.json` del replay aparecen **89 registros con espacios de nombre modded**
(`customNamespacesForRegistries`), de Create y decenas de addons. Al reabrir el replay,
Flashback pide a vanilla que reconstruya esos registros. Bajo **Connector + Forgified Fabric
API** intervienen además varios mixins de Fabric sobre `RegistryDataLoader`
(`fabric-registry-sync`, `fabric-resource-conditions`, etc.), y basta con que **una** entrada
modded no resuelva para que el cargador estricto tire todo abajo.

Es importante: el detalle de *qué* entradas concretas fallan se escribe en `latest.log`
**justo antes** de la línea `Failed to load registries`. En los archivos que me pasaste ese
log estaba cortado (el juego seguía escribiendo cuando se copió), así que el arreglo se diseñó
para ser **tolerante de forma general**, no para una entrada concreta. Si quieres la causa raíz
exacta, ver §6.

### Lo que NO es

- No es el menú de Flashback en sí (ese abre bien).
- No es el mod `modfix` que ya tienes: ese es en realidad **grapplinfix** (otra cosa).
- Los errores `dndecor ... Couldn't parse element loot_table` del log son de arranque normal
  (loot tables rotas de ese mod), se ignoran y **no** son la causa de este crash.

---

## 3. Qué hace el mod de arreglo

Un único Mixin sobre `RegistryDataLoader#load(...)` intercepta la comprobación "¿hubo
errores?". **Solo cuando la carga la dispara Flashback** (se detecta mirando la pila de
llamadas), se responde "no hubo errores", de modo que vanilla **no lanza la excepción** y
devuelve los registros que sí se cargaron. Las entradas problemáticas quedan fuera del mundo
del replay.

- Fuera de Flashback (crear/cargar mundos normales) **no cambia nada**: comportamiento vanilla.
- Es un arreglo de **cliente** (la edición de replays es cliente).
- Registra un `WARN` cuando omite algo, para que sepas que actuó.

Trade-off: si un bioma/tipo de daño/etc. modded no carga, podría faltar en el replay (p. ej.
una entidad con datos incompletos). Para *ver y grabar* suele ser aceptable.

---

## 4. Evaluación de viabilidad: ¿Flashback 100% funcional?

Conviene separar por fases, porque "que abra" y "que funcione todo" son cosas distintas.

### Fase 1 — Abrir el replay (el crash actual)
**Probabilidad de éxito con este fix: alta (~80%).**
El fix ataca exactamente el punto que está reventando. El 20% de riesgo es que, tras omitir
las entradas malas, la carga del mundo falle más adelante por *otra* razón (p. ej. un registro
que quedó a medias y un mod lo da por hecho).

### Fase 2 — Que el servidor de replay corra sin crashear (Create)
**Riesgo: medio.** Hay un problema conocido (Sinytra Connector #2075): Create envía paquetes
`chain_conveyor` en el tick del servidor, y el servidor de replay de Flashback puede no tener
esos canales de red registrados, lanzando `Payload ... may not be sent to the client`. Tú usas
Create 6.0.10 (más nuevo que el del reporte), así que **puede** estar mitigado o no. Esto solo
se sabrá **después** de superar la Fase 1. Si aparece, se arregla con un segundo Mixin
(planificado como Fase 2; ver §5).

### Fase 3 — Reproducir, mover la cámara y editar
**Riesgo: medio.** Con tantos mods de render (Iris, Veil, Sodium, Flywheel/Ponder, ETF/EMF…)
puede haber fallos visuales o algún crash de render al reproducir. Suelen ser puntuales y a
menudo cosméticos, pero en un pack tan grande no se puede garantizar "perfecto".

### Fase 4 — Exportar vídeo
**Riesgo: bajo–medio** si las fases anteriores van bien. La exportación usa el render normal;
si el replay reproduce, normalmente exporta.

### Veredicto honesto
- **Conseguir que el replay ABRA y se REPRODUZCA:** muy factible (~80%).
- **Flashback "completamente funcional" de punta a punta** en este pack con Connector, sin
  tocar nada más: **parcial, ~50–70%.** Es realista esperar **1–2 iteraciones** (arreglar el
  crash de registros y, si aparece, el de paquetes de Create).
- **Camino más seguro a "100%"**, si necesitas fiabilidad total: grabar/editar con un
  **perfil de mods reducido** (los imprescindibles para la escena que grabas), lo que baja
  muchísimo la probabilidad de conflictos. Connector siempre añade una capa de fragilidad
  frente a un Flashback nativo en Fabric.

---

## 5. Plan por fases

**Fase 1 (hecha): arreglo de registros.**
Compilar este mod, ponerlo en `mods`, probar a abrir el replay.
→ Resultado esperado: el replay abre (con posible `WARN` de entradas omitidas).

**Fase 2 (si aparece el crash de Create `chain_conveyor`):**
Añadir un segundo Mixin que, cuando el servidor sea el de replay de Flashback, ignore el envío
de esos paquetes clientbound (o relaje `NetworkRegistry.checkPacket`). Es más específico de
Create/NeoForge y conviene escribirlo **viendo el crash real**, no a ciegas.

**Fase 3 (si hay fallos de render al reproducir):**
Identificar el mod de render culpable por el crash y decidir entre desactivarlo para grabar o
añadir un guard puntual.

En cada fase, el método es el mismo: reproducir, leer `latest.log`/crash report, atacar el
punto exacto. Pásame el nuevo log y lo iteramos.

---

## 6. Cómo obtener la causa raíz EXACTA (opcional, para afinar)

Si quieres saber qué entradas concretas fallan (para, por ejemplo, arreglar el mod de raíz o
reportarlo):

1. Quita temporalmente el `.jar` del fix de `mods`.
2. Reproduce el crash (abre el replay) y deja que se cierre el juego.
3. Abre `logs\latest.log` y busca las líneas **anteriores** a
   `Failed to load registries due to above errors`. Verás entradas tipo
   `Couldn't parse element ResourceKey[...] : ...` que dicen **mod** y **registro** culpables.
4. Comparte esas líneas y se puede valorar un arreglo dirigido (o quitar/actualizar ese mod)
   en vez de la tolerancia general.

---

## 7. Alternativas

- **Perfil de grabación reducido:** una segunda instancia/configuración con Create + lo mínimo
  para tu escena y Flashback. Mucho más estable para producir vídeo.
- **Actualizar piezas clave:** vigilar nuevas versiones de Connector y Flashback; varias de
  estas incompatibilidades se han ido arreglando versión a versión.
- **Reportar con el log exacto** (§6) a Sinytra Connector y/o al mod culpable: es la vía para
  un arreglo "oficial" y permanente.

---

## Fuentes (incompatibilidades conocidas)

- Sinytra Connector #1678 — crash al crear/unirse a mundo/replay con Flashback:
  https://github.com/Sinytra/Connector/issues/1678
- Sinytra Connector #2075 — ReplayServer crashea con Create (paquetes chain_conveyor):
  https://github.com/Sinytra/Connector/issues/2075
- Sinytra Connector #1987 — incompatibilidad general con Flashback:
  https://github.com/Sinytra/Connector/issues/1987
- Jade #477 — crash al entrar a un replay con Connector:
  https://github.com/Snownee/Jade/issues/477
- Flashback (changelog 0.39.x — carga de registros más tolerante):
  https://modrinth.com/mod/flashback/versions
