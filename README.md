# Flashback Connector Fix

Mod de NeoForge **1.21.1** que arregla el crash de **Flashback** al abrir un replay cuando
se ejecuta sobre **NeoForge + Sinytra Connector** en un pack pesado (Create y addons).

- **Crash que arregla:** `IllegalStateException: Failed to load registries due to above errors`
  (al hacer click en un replay → `Flashback.openReplayWorld`).
- **Cómo:** un Mixin hace **tolerante** la carga de registros dinámicos del replay: omite las
  entradas modded que no se pueden parsear en vez de abortar toda la carga. Fuera de Flashback,
  el juego se comporta igual que vanilla.

> El diagnóstico completo y la evaluación de "¿se puede dejar Flashback 100% funcional?" están en
> **`PLAN-Y-VIABILIDAD.md`**.

---

## ESTADO ACTUAL (build 4 + config ModernFix) — léelo

🚀 **Gran avance: ya superamos TODA la carga de datapacks.** Build 4 dejó atrás registros,
loot y recetas, y llegó hasta el **arranque del servidor de replay**. Ahí apareció un crash
**distinto** (ya no de datapacks):

```
NullPointerException: ...IChunkGenerator.mfix$setStrongholdCachePath(...) because "instance" is null
  en ServerLevel.<init> -> ReplayServer.loadLevel -> ReplayServer.initServer
```

Es una optimización de **ModernFix** (`perf.cache_strongholds`) que asume un generador de
chunks no-nulo; el servidor de replay de Flashback lo tiene nulo → NPE. **No es un fallo del
mod fix.** Se arregla desactivando ese mixin de ModernFix.

✅ **Ya lo hice por ti**: añadí `mixin.perf.cache_strongholds=false` en
`G:\CurseForge\Instances\Prueba de Modpack para Servidor\config\modernfix-mixins.properties`.
No necesitas recompilar nada para esto; solo **reiniciar el juego**. (Impacto de desactivarlo:
insignificante — solo cachea ubicaciones de fortalezas.)

**Siguiente paso ahora mismo:** NO hace falta recompilar. Mantén el `.jar` build 4 en `mods`,
**reinicia el juego** y abre el replay.
- **Si abre:** 🎉 ¡lo logramos! Avísame y revisamos reproducción/edición/exportación.
- **Si crashea otra vez:** mándame `logs\latest.log`. Cada vez llegamos más lejos.

---

### Histórico: los 3 crashes de datapacks (resueltos por el mod)

Progreso real, tres crashes superados, mismo origen de fondo:

✅ **Crash 1 (registros dinámicos):** resuelto. Omite 5 entradas de `moonlight:soft_fluid`.
✅ **Crash 2 (loot tables):** resuelto. En tu última prueba omitió **20** loot tables rotas
(`AirItem cannot be cast to Holder.Reference`) — y lo hizo en un hilo *worker*, así que la
detección entre hilos funciona.
✅ **Crash 3 (recetas):** arreglado en esta build (`RecipeManagerMixin`). El cast era
`EmptyFluid cannot be cast to Holder.Reference` al cargar recetas.

### La causa de fondo (importante y honesto)

Los tres crashes son **el mismo problema sistémico de Sinytra Connector**: al reconstruir el
mundo del replay, un códec de vanilla (`ExtraCodecs$4`) intenta convertir un valor de registro
a `Holder.Reference`, pero bajo Connector recibe el valor "por defecto" del registro
(`air`, `empty`, …) y revienta. Eso aparece en **cada subsistema de datapack** que se recarga:
registros → loot → recetas → (posiblemente avances u otros).

Mi mod va haciendo **tolerante** cada subsistema (omite lo que falla y sigue). Es la estrategia
correcta, pero **puede que aparezca un 4º crash** del mismo tipo en otro subsistema. Si pasa, se
arregla igual (un Mixin más). Soy transparente: dejar Flashback **100% funcional** en un pack tan
grande con Connector no está garantizado; lo que sí vamos logrando es acercarnos paso a paso a
que el replay **abra**.

**Siguiente paso:**

1. **Recompila** (`compilar.bat`, sobrescribe el `.jar` en `mods`).
2. Deja el flag `-XX:-OmitStackTraceInFastThrow` puesto (por si aparece otro cast, para verlo).
3. Abre el replay.
   - **Si abre:** 🎉 avísame y pasamos a verificar reproducción/edición/exportación.
   - **Si crashea otra vez:** mándame `logs\latest.log`. Si es el mismo patrón en otro
     subsistema, es un Mixin más (rápido).

### Si prefieres no seguir iterando

Es una opción legítima. Alternativas más fiables para conseguir grabaciones:
- Grabar con un **perfil de mods reducido** (Create + lo mínimo de tu escena + Flashback): mucho
  menos superficie para estos fallos de Connector.
- **Reportar** la causa raíz (`X cannot be cast to Holder.Reference` al abrir replays) a Sinytra
  Connector con el log: es un bug suyo de fondo y el arreglo permanente debería venir de ahí.

---

## Requisitos para compilar

- **JDK 21** (Temurin/Adoptium recomendado). Tu juego ya usa Java 21.
- Conexión a internet (Gradle descarga NeoForge y Minecraft la primera vez).
- No necesitas tener Gradle instalado si generas el *wrapper* (ver abajo).

## Compilar — opción fácil (recomendada)

**Haz doble click en `compilar.bat`** (o ejecútalo desde una terminal). Ese script:

1. Comprueba que tengas Java.
2. Si no tienes Gradle, **lo descarga solo** (una vez) y genera el wrapper.
3. Compila el mod en la **misma ventana** y te dice claramente **EXITO** o **FALLO**.
4. Si todo va bien, te ofrece **copiar el `.jar` directo a la carpeta `mods`** del modpack.

La ventana se queda abierta al final para que puedas leer el resultado.

## Compilar — paso a paso (manual)

1. Abre una terminal en esta carpeta:
   `F:\Proyectos Visual Studio\Mods Minecraft\Mod Fix Flashback with Sinytra`

2. **Genera el Gradle Wrapper** (solo la primera vez). Necesitas Gradle en el sistema **o**
   hazlo desde tu IDE (ver más abajo). Si tienes Gradle instalado:
   ```
   gradle wrapper --gradle-version 8.10.2
   ```
   Esto crea `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar`.

3. **Compila el mod:**
   ```
   .\gradlew.bat build
   ```

4. El `.jar` final queda en:
   ```
   build\libs\flashbackconnectorfix-1.0.0.jar
   ```
   (Ignora cualquier `*-sources.jar`.)

### Alternativa con IDE (más fácil)

- **IntelliJ IDEA:** *File → Open* esta carpeta → acepta importar el proyecto Gradle.
  IntelliJ descarga el wrapper y las dependencias solo. Luego: pestaña **Gradle → Tasks → build → build**.
- También puedes lanzar el juego de pruebas con la tarea **runs → runClient**.

> ¿No quieres usar el wrapper? Con Gradle del sistema instalado funciona igual:
> `gradle build` en vez de `.\gradlew.bat build`.

---

## Instalar en tu modpack

Copia el `.jar` compilado a la carpeta de mods de tu instancia:

```
G:\CurseForge\Instances\Prueba de Modpack para Servidor\mods\
```

Arranca el juego, graba algo con Flashback y abre el replay desde el menú.

- Solo hace falta en el **cliente** (la edición de replays es del cliente). Es inocuo si
  también lo dejas en el servidor.
- Si todo va bien verás en el log:
  `[FlashbackConnectorFix] Cargado. Tolerancia de registros para replays de Flashback ACTIVA.`
- Si había registros problemáticos, al abrir el replay verás un `WARN` indicando que se
  omitieron (en lugar de un crash).

---

## Si el build falla por el Mixin (poco probable)

El Mixin apunta al método central `RegistryDataLoader#load(...)`. Si una futura versión de
Minecraft/NeoForge cambiara su firma, el Mixin podría no encontrar el objetivo. En ese caso,
en `src/main/java/com/lucerion/flashbackfix/mixin/RegistryDataLoaderMixin.java` cambia el
`method = "..."` por `method = "load"` y añade `ordinal = 0` al `@At` del `isEmpty()`.
Con NeoForge 21.1.230 (tu versión) el descriptor actual es el correcto.

---

## Cómo desinstalar / diagnosticar

Para ver **exactamente** qué registros fallan, quita temporalmente este `.jar`, reproduce el
crash y abre `logs\latest.log`: busca las líneas justo **antes** de
`Failed to load registries due to above errors`. Esas líneas (`Couldn't parse element ...`)
dicen qué mod y qué entrada son el origen, por si prefieres atacar la causa de raíz.
