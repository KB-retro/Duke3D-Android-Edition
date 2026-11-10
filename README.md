# Duke3D Android Edition

Duke Nukem 3D para Android con los archivos de tu copia de **Duke Nukem 3D: Megaton Edition**
(Steam), incluidas las expansiones *Duke It Out in D.C.*, *Nuclear Winter* y *Caribbean: Life's
a Beach*. El motor es [EDuke32](https://www.eduke32.com) (GPL v2).

El APK **no incluye ningún archivo del juego**: hay que copiarlos de tu copia de Steam.

## Cómo jugar

1. Instala el APK.
2. En Steam: clic derecho en *Duke Nukem 3D: Megaton Edition* → *Administrar* → *Ver archivos
   locales*. Copia la carpeta **`gameroot`** al móvil o consola, dentro de una carpeta
   **`Duke3D`** en la raíz de la memoria:

   ```
   /storage/emulated/0/Duke3D/gameroot/DUKE3D.GRP
   /storage/emulated/0/Duke3D/gameroot/addons/dc/…
   /storage/emulated/0/Duke3D/gameroot/addons/nw/…
   /storage/emulated/0/Duke3D/gameroot/addons/vacation/…
   ```

   También vale copiar solo el contenido de `gameroot` directamente en `Duke3D`.

   ¿Prefieres otra carpeta? Con **Elegir carpeta** la app lee los archivos de la carpeta que
   quieras, con el nombre que quieras (también en la tarjeta SD).
3. Abre la app, da el permiso de archivos y elige el juego o la expansión.

Si el juego se cierra, al volver al menú la app enseña las últimas líneas del registro y lo
guarda entero en `registro.txt`, dentro de la carpeta del juego.

La configuración y las partidas guardadas se quedan en la misma carpeta del juego.

## Controles

- **Mando:** los controles por defecto de EDuke32 para mando (se cambian en el menú del juego).
- **Atrás** abre y cierra el menú del juego.
- Todavía no hay controles táctiles.

## Compilar

Con GitHub Actions: **Actions → Android → Run workflow**. El APK sale como artefacto del run.

Opcional, para que cada APK se instale encima del anterior, añade estos secretos del
repositorio (si no, se firma con una clave de depuración distinta en cada compilación):

- `SIGNING_KEYSTORE_BASE64`: el `.p12` en base64.
- `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (por defecto `duke3d`) y `SIGNING_KEY_PASSWORD`.

## Estructura

- `eduke32/`: código de EDuke32 con los cambios para Android (ver `eduke32/PARCHES.md`).
- `android/`: proyecto Android (lanzador en Java, SDL2 y la compilación con CMake).

## Licencias y aviso legal

- EDuke32: GPL v2 (`eduke32/gpl-2.0.txt`) y licencia del motor BUILD (`eduke32/buildlic.txt`).
  La licencia BUILD no permite uso comercial: este proyecto es gratuito.
- SDL2: licencia zlib.
- Duke Nukem 3D y sus expansiones son de sus respectivos dueños. Este proyecto no está
  afiliado a ellos ni distribuye ningún archivo del juego.
