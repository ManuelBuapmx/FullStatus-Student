# FullStatus Student

App Android del alumno de **PRISMAL MESH**. Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.

El alumno entra con su matrícula y contraseña, ve sus materias, sus asistencias y calificaciones por unidad, y presenta los exámenes que el profesor le asignó. **Es solo para ver y presentar exámenes:** los puntajes, las ponderaciones, las unidades y las asignaciones los controla el profesor desde **FullStatus** (el programa de administrador).

Última actualización de este documento: 25/oct/2026.

---

## 1. Cómo funciona (en corto)

- La app es un "cascarón" nativo de Android (`MainActivity.java`) que muestra en un WebView la app real: `src/main/assets/index.html`.
- Todo lo sensible (contraseñas, respuestas correctas, calificaciones, asistencias de otros alumnos) vive en **Supabase** y solo se toca con funciones RPC del servidor. La app nunca lee tablas sensibles directo.
- Las reglas anti-trampa que HTML/JS no puede hacer solo las hace Android (ver sección 3).

## 2. Pantallas

1. **Acceso:** matrícula + contraseña (con botón "Ver"). Debajo, el enlace **Crear contraseña**. Matrícula inexistente: "Matrícula no registrada". 5 contraseñas incorrectas seguidas bloquean la matrícula 10 minutos.
2. **Crear contraseña:** matrícula, contraseña (mínimo 6 caracteres) y repetirla. Solo se puede crear **una vez**; si el alumno la olvida, el profesor la restablece. Al guardar, regresa al acceso para entrar con matrícula y contraseña.
3. **Inicio:** datos del alumno (nombre, matrícula, grado, grupo y fecha de hoy). Un botón de tres rayas en la esquina izquierda abre un panel lateral (mitad de la pantalla) con **Materias** y, hasta abajo, **Cerrar sesión**. El grado y el grupo salen de `alumnos.grupo` ("10B" → grado 10, grupo B).
4. **Materia:** un bloque por **unidad**: calificación sobre 10 con desglose, y asistencias/faltas/retardos con el detalle **solo de las fechas de esa unidad**. Botón **Exámenes**.
5. **Exámenes:** exámenes de la materia agrupados por unidad y, dentro de cada unidad, en el orden Ordinario → Remedial → Extraordinario → Extraordinario 2. Solo se activan los que el profesor asignó.
6. **Examen:** reloj (el tiempo restante lo manda el servidor), preguntas por sección, avance guardado en el servidor mientras contesta y resultado al terminar.

Cada alumno ve **solo sus materias** (las que tienen asistencias registradas más los exámenes que tenga asignados).

## 3. Reglas anti-trampa (Android)

Mientras el examen está activo (`examenActivo == true`), cualquier cosa sospechosa llama a `anularExamen()`, que cierra la app por completo:

- `FLAG_SECURE`: sin capturas ni grabación de pantalla (aparece negra en Recientes).
- Perder el foco (notificaciones, ventanas flotantes, ajustes rápidos) o ir a Inicio/Recientes cierra la app.
- El botón Atrás no hace nada durante el examen.
- Sin multiventana, orientación fija en vertical, sin menú de copiar/pegar.

La vigilancia empieza solo al pulsar "Comenzar" (`Android.iniciarExamen()`) y termina al entregar (`Android.terminarExamen()`).

**Reanudaciones:** si el alumno sale del examen, el intento queda abierto. Al volver cuenta como una *reanudación* (máximo `max_reanudaciones`, hoy 2), recupera las respuestas guardadas y el tiempo **no se reinicia**. Al pasar el máximo, el examen se anula con 0. Si se acaba el tiempo, el servidor califica el avance guardado.

**Firma de las llamadas:** `iniciar_intento`, `obtener_preguntas`, `guardar_progreso` y `calificar_examen` exigen una firma HMAC-SHA256 de `accion|matricula|materiaId|ts`. La calcula `Puente.firmar()` en `MainActivity.java` con la clave `BuildConfig.FIRMA_SECRETO`, que llega del secreto de GitHub **`CMK_FIRMA_SECRETO`** y nunca se expone al JavaScript. El servidor solo acepta firmas recientes (la hora del celular debe estar bien). Fuera de la app (en un navegador) los exámenes no funcionan porque no hay firma.

## 4. Archivos del repositorio

| Archivo | Para qué sirve |
|---|---|
| `src/main/java/com/prismalmesh/checkmyknowledge/MainActivity.java` | Única Activity: WebView, reglas anti-trampa y puente JS (`iniciarExamen`, `terminarExamen`, `firmar`, `salir`). |
| `src/main/assets/index.html` | La app del alumno (pantallas, llamadas a Supabase). |
| `src/main/AndroidManifest.xml` | Único permiso: `INTERNET`. Sin cámara, micrófono, almacenamiento ni ubicación. |

El `applicationId` (`com.prismalmesh.checkmyknowledge`) **no se cambia a propósito**: cambiarlo haría que Android trate la app como otra distinta. Los videos de YouTube se cargan con origen `https://<applicationId>` (ver `origenBase()` en `MainActivity.java`).

## 5. Servidor (Supabase, proyecto `checkmyknowledge`)

**Tablas** (con RLS cerrada: la app no las lee directo)

| Tabla | Contenido |
|---|---|
| `alumnos` | matrícula, nombre, grupo, activo, hash de contraseña, intentos fallidos y bloqueo. |
| `asistencias` | marcas por alumno y fecha (P, A, R; falta justificada). Las publica FullStatus. |
| `unidades` | unidades de cada grupo y materia con sus fechas desde/hasta. Las publica FullStatus. |
| `calificaciones_unidad` | puntos por unidad: asistencias, actividades, participaciones y examen, más conteos. Las publica FullStatus. |
| `materias` | cada fila es **un examen** (nombre, activa, ventana de fechas, `solo_asignados`). |
| `preguntas` | preguntas de cada examen (la respuesta correcta nunca sale del servidor). |
| `asignaciones` | qué alumno puede presentar qué examen (y su ventana de fechas propia). |
| `intentos` / `resultados` | intento en curso (avance, reanudaciones) y resultado final. Uno por alumno y examen. |
| `materia_unidades` | cuántas unidades tiene cada materia (Inglés I: 2, Inglés VI: 3, Inglés IX: 2, Ética Profesional: 3). |
| `config` | ajustes (ver sección 8). |
| `admins` | usuarios con permiso de publicar y restablecer contraseñas. |

**Funciones que usa la app del alumno:** `validar_acceso`, `crear_contrasena`, `mis_materias`, `consultar_asistencias`, `mis_calificaciones`, `mis_examenes`, `iniciar_intento`, `obtener_preguntas`, `guardar_progreso`, `calificar_examen`.

**Funciones solo para admins (FullStatus):** `publicar_asistencias`, `publicar_calificaciones`, `restablecer_contrasena`.

> Aviso de seguridad: la contraseña protege el acceso desde la app y los exámenes van firmados, pero las funciones de solo lectura (`mis_materias`, `consultar_asistencias`, `mis_calificaciones`, `mis_examenes`) aceptan solo la matrícula. Cerrar esto por completo requeriría una sesión por alumno.

## 6. Cómo nombrar los exámenes

La **materia, la unidad y el tipo** de un examen se reconocen por su **nombre** en la tabla `materias`:

```
INGLÉS I - Unidad 1 - Ordinario
INGLÉS I - Unidad 1 - Remedial
INGLÉS I - Unidad 2 - Extraordinario
INGLÉS VI - Unidad 3 - Extraordinario 2
ÉTICA PROFESIONAL - Unidad 1 - Ordinario
```

- **Ordinario:** para todos. Aparece siempre; se activa si está asignado o abierto a todos (`solo_asignados = false`).
- **Remedial:** alumnos que reprobaron la materia (menos de 7 en la unidad).
- **Extraordinario:** alumnos que reprobaron el remedial.
- **Extraordinario 2:** alumnos que reprobaron el extraordinario.
- Remedial y extraordinarios **solo aparecen si el profesor se los asignó** al alumno. La asignación es manual.
- Un nombre sin tipo cuenta como Ordinario; sin "Unidad N", el examen sale primero y sin encabezado de unidad.
- Si una unidad aún no tiene examen creado, el alumno ve su Ordinario en gris: "Aún no hay examen para esta unidad".
- Para que una unidad nueva aparezca, hay que registrar la materia en `materia_unidades`.

## 7. Datos que publica FullStatus

- **Asistencias y unidades:** `publicar_asistencias`. Reemplaza todo el grupo en cada envío. Los alumnos se enlazan con la base por nombre normalizado (con desempate por grupo).
- **Calificaciones por unidad:** `publicar_calificaciones`, mismo estilo. La calificación de la unidad es la suma de asistencias + actividades + participaciones + examen (sobre 10). Los puntos y las ponderaciones los calcula FullStatus; la app solo los muestra.

Formato de `publicar_calificaciones`:

```json
{ "grupos": [ { "id": "<id del grupo en FullStatus>", "nombre": "7B", "materia": "INGLÉS VI",
  "alumnos": [ { "nombre": "APELLIDOS NOMBRE",
    "unidades": [ { "nombre": "Unidad 1", "asistencias": 1.8, "actividades": 2.5,
      "participaciones": 0.7, "examen": null,
      "n_actividades": 4, "total_actividades": 5, "n_participaciones": 3 } ] } ] } ] }
```

Estado de la unidad en la app: **En curso** mientras falte el examen; con examen, **Aprobada** si la suma es 7 o más y **En remedial** si es menor al mínimo.

## 8. Ajustes (`config`)

| Clave | Para qué |
|---|---|
| `exigir_firma` | `true`: el servidor exige la firma en las llamadas del examen. |
| `firma_secreto` | Clave de la firma. **Secreta; debe ser la misma que `CMK_FIRMA_SECRETO`.** |
| `max_reanudaciones` | Veces que un alumno puede reanudar un examen (hoy 2). |
| `minimo_aprobatorio` | Calificación mínima para no estar en remedial (hoy 7). |
| `grupo_prueba` | Grupo de pruebas (hoy `12F`). |

## 9. Grupo de prueba

Los alumnos del grupo indicado en `config.grupo_prueba` (hoy **12F**) ven **todas las materias** y **todos los exámenes**, sin asignación ni límite de fechas, para simular la app como la vería un alumno. Cada examen se puede presentar una sola vez; para repetir uno hay que borrar su fila en `intentos` y en `resultados`.

## 10. Compilar

El APK se compila con GitHub Actions. Requiere el secreto **`CMK_FIRMA_SECRETO`** en el repositorio. Después de subir cambios a `src/main/assets/index.html` (o a los demás archivos), hay que esperar a que termine el build e instalar el APK nuevo en el celular; si no, el celular sigue con la versión anterior.

## 11. Pendientes

- Que FullStatus publique las calificaciones por unidad con `publicar_calificaciones` (la app y el servidor ya están listos).
- Botón en FullStatus para restablecer la contraseña de un alumno (hoy se puede desde Supabase con `restablecer_contrasena`).
- Crear los exámenes Ordinarios de cada unidad con el nombre indicado en la sección 6.
- Decidir si el examen Remedial se activa solo cuando el alumno saca menos de 7 (hoy es manual).
- Dar de alta el grupo 4C y sus asistencias de Ética Profesional.
- Opcional: simplificar las pantallas (menos botones, aviso de examen disponible, entrar directo si solo hay una materia) y que el botón Atrás del celular cierre el panel lateral en vez de salir.
