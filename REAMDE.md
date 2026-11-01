# Examen - Paralelización en CPU

## Descripción
Reproductor multimedia que separa la decodificación, el procesamiento de audio
y la reproducción mediante hilos concurrentes y colas bloqueantes.

## Componentes principales
- ServicioDecodificador: decodifica video y audio.
- ControladorReproductor: coordina los hilos de procesamiento y reproducción.
- Cola de audio procesado: desacopla el procesamiento DSP de la reproducción.
- Efecto de eco: procesamiento aplicado al audio antes de reproducirlo.

## Ejecución
1. Abrir el proyecto en el IDE.
2. Ejecutar la clase principal.
3. Seleccionar un archivo MP4.
4. Verificar la reproducción y las métricas de procesamiento.