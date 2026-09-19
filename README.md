# Flashback Fix

Mod de cliente para **NeoForge 1.21.1** que hace funcionar
[Flashback](https://modrinth.com/mod/flashback) (mod de Fabric, cargado con Sinytra Connector +
Forgified Fabric API) en un modpack pesado con **Create**, **Sable / Create Aeronautics**,
**Iris** y **Sodium**.

Autor: **ChristemDc**

## Qué arregla

**Apertura y estabilidad de replays**
- Abre replays aunque haya recetas, loot o datos modded que Connector no puede re-parsear: esas
  entradas se omiten en vez de abortar la carga.
- Tolerancia de red: si un paquete de algún mod no se puede grabar o reproducir (típico cuando el
  servidor usa una versión distinta de un mod), se descarta ese paquete en vez de cerrar el juego
  o el replay.
- Evita crashes del servidor de reproducción con entidades recreadas sin datos (contraptions,
  planos de Create) y con la optimización `cache_strongholds` de ModernFix.

**Fidelidad visual**
- Items y bloques modded correctos: al conectarte a un servidor se guarda su mapa de IDs de
  registro, y se aplica al reproducir un replay grabado allí.
- Naves de Sable / Aeronautics completas: casco, interior con bloques de Create, iluminación,
  piezas móviles (hélices, timones), jugadores sentados, y movimiento correcto al avanzar y al
  retroceder en la línea de tiempo.
- Contraptions de Create visibles y estables al saltar en la línea de tiempo.
- Prefijos de equipos del scoreboard y contenido dinámico de `cretania_recipes`.
- Compatibilidad con Iris 1.8.14 (suprime el mixin de Flashback que ya no encaja).

**Rendimiento**
- Elimina los tirones causados por paquetes de equipos incoherentes (cada uno generaba un informe
  de crash completo en NeoForge) y por recargas repetidas del renderer.

## Requisitos

- Minecraft 1.21.1 + NeoForge 21.1.x
- Flashback 0.39.5 para MC 1.21.1
- Sinytra Connector + Forgified Fabric API
- Opcionales: Create 6, Sable 2.0.3 / Create Aeronautics, Iris, ModernFix

Es solo de cliente: se puede entrar a servidores que no lo tengan.

## Notas

- Las naves, sus piezas y los pasajeros se capturan **al grabar**: las grabaciones hechas con una
  versión anterior del mod no los tienen.
- Este repositorio no incluye Flashback ni ningún código suyo; el mod se integra con él mediante
  mixins en tiempo de ejecución.

## Compilar

```
gradlew build
```

El jar queda en `build/libs/`. Requiere JDK 21.
