package controladores;

import org.bytedeco.javacv.Java2DFrameConverter;

import servicios.ServicioDecodificador;
import servicios.TiempoServicio;
import servicios.Util;
import vistas.VistaReproductor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import java.awt.image.BufferedImage;

public class ControladorReproductor {

    // Intervalo entre actualizaciones de telemetría.
    private final int TIEMPO_PAUSA = 200;

    private final VistaReproductor vista;
    private final ServicioDecodificador servicio;

    // Último tiempo de video reportado en telemetría.
    private volatile long ultimoTiempo;

    /*
     * Reloj maestro basado en el audio.
     *
     * timestampInicioAudio:
     * Timestamp del primer bloque de audio reproducido.
     *
     * tiempoInicioReproduccion:
     * Tiempo de reloj del sistema en que comenzó la reproducción.
     */
    private volatile long timestampInicioAudio = -1;
    private volatile long tiempoInicioReproduccion = -1;

    public ControladorReproductor(
            VistaReproductor vista,
            ServicioDecodificador servicio) {

        this.vista = vista;
        this.servicio = servicio;

        // Registrar el evento para seleccionar y reproducir un video.
        this.vista.setSelecionarClick(
                evento -> iniciarProceso()
        );
    }

    // ---------------------------------------------------------
    // HILO CONSUMIDOR DE VIDEO
    // ---------------------------------------------------------

    /**
     * Consume frames decodificados y los presenta en la vista.
     * El video se sincroniza con el reloj maestro del audio.
     */
    private void cicloRenderizado() {

        try (var conversor = new Java2DFrameConverter()) {

            // Esperar hasta que el audio establezca el reloj maestro.
            while (servicio.isEjecutando()
                    && tiempoInicioReproduccion < 0
                    && !servicio.isDecodificacionTerminada()) {

                Thread.sleep(2);
            }

            while (servicio.isEjecutando()
                    && (!servicio.isDecodificacionTerminada()
                    || servicio.hayVideoPendiente())) {

                ServicioDecodificador.ContenedorFrame contenedor =
                        servicio.getSiguienteFrame();

                if (contenedor == null) {
                    continue;
                }

                // Timestamp del frame en microsegundos.
                long timestampFrame =
                        contenedor.timestampMicros();

                /*
                 * Si todavía no se estableció el reloj de audio,
                 * no se puede sincronizar este frame.
                 */
                if (timestampInicioAudio < 0
                        || tiempoInicioReproduccion < 0) {

                    contenedor.frame().close();
                    continue;
                }

                // Calcular el instante objetivo de presentación.
                long tiempoObjetivo =
                        tiempoInicioReproduccion
                                + (timestampFrame - timestampInicioAudio)
                                * 1000;

                // Esperar hasta el momento de mostrar el frame.
                esperarHasta(tiempoObjetivo);

                if (!servicio.isEjecutando()) {
                    contenedor.frame().close();
                    break;
                }

                ultimoTiempo = timestampFrame / 1000;

                // Convertir el frame a imagen para la interfaz.
                BufferedImage imagen =
                        conversor.convert(contenedor.frame());

                vista.actualizarImagenVideo(imagen);

                // Liberar el frame después de presentarlo.
                contenedor.frame().close();
            }

        } catch (InterruptedException ex) {

            Thread.currentThread().interrupt();

        } catch (Exception ex) {

            System.err.println(
                    "[Controlador] Error en renderizado: "
                            + ex.getMessage()
            );
        }
    }

    /**
     * Espera hasta el instante objetivo usando el reloj del sistema.
     */
    private void esperarHasta(long tiempoObjetivo)
            throws InterruptedException {

        while (servicio.isEjecutando()) {

            long restante =
                    tiempoObjetivo - System.nanoTime();

            if (restante <= 0) {
                break;
            }

            long milisegundos = restante / 1_000_000;

            int nanosegundos =
                    (int) (restante % 1_000_000);

            Thread.sleep(
                    milisegundos,
                    nanosegundos
            );
        }
    }

    // ---------------------------------------------------------
    // HILO DE TELEMETRÍA
    // ---------------------------------------------------------

    /**
     * Actualiza las métricas del reproductor periódicamente.
     */
    private void cicloTelemetria() {

        while (servicio.isEjecutando()) {

            ServicioDecodificador.Metricas metricas =
                    servicio.getMetricas(ultimoTiempo);

            vista.actualizarTelemetria(metricas);

            TiempoServicio.pausarMilisegundos(TIEMPO_PAUSA);
        }
    }

    // ---------------------------------------------------------
    // INICIO DE LOS HILOS
    // ---------------------------------------------------------

    /**
     * Inicia la decodificación y los hilos consumidores.
     */
    private void iniciarProceso() {

        var archivo = vista.solicitarArchivoVideo();

        if (archivo == null) {
            return;
        }

        // Reiniciar el reloj maestro para el nuevo video.
        timestampInicioAudio = -1;
        tiempoInicioReproduccion = -1;
        ultimoTiempo = 0;

        // 1. Hilo productor de decodificación.
        Thread hiloDecodificacion = new Thread(
                () -> servicio.iniciarDecodificacion(
                        archivo.getAbsolutePath()
                ),
                "Hilo Decodificacion"
        );

        hiloDecodificacion.start();

        /*
         * Esperar hasta que el servicio comience a ejecutarse.
         * Esto evita iniciar consumidores antes de la decodificación.
         */
        while (!servicio.isEjecutando()
                && hiloDecodificacion.isAlive()) {

            TiempoServicio.pausarMilisegundos(1);
        }

        // 2. Consumidor de video.
        new Thread(
                this::cicloRenderizado,
                "Hilo Renderizado"
        ).start();

        // 3. Hilo independiente para procesamiento DSP.
        new Thread(
                this::cicloProcesamientoAudio,
                "Hilo DSP Audio"
        ).start();

        // 4. Consumidor de audio: solo salida al hardware.
        new Thread(
                this::cicloAudio,
                "Hilo Audio"
        ).start();

        // 5. Hilo de telemetría.
        new Thread(
                this::cicloTelemetria,
                "Hilo Telemetria"
        ).start();
    }

    // ---------------------------------------------------------
    // HILO CONSUMIDOR DE AUDIO
    // ---------------------------------------------------------

    /**
     * Consume audio ya procesado por el hilo DSP.
     *
     * Este hilo NO aplica eco ni realiza cálculos matemáticos.
     * Su responsabilidad es:
     * 1. Obtener audio de colaAudioProcesado.
     * 2. Configurar SourceDataLine.
     * 3. Convertir muestras a PCM.
     * 4. Escribir los bytes al hardware.
     */
    private void cicloAudio() {

        SourceDataLine lineaAudio = null;

        try {

            while (servicio.isEjecutando()
                    && (!servicio.isProcesamientoAudioTerminado()
                    || servicio.hayAudioProcesadoPendiente())) {

                /*
                 * Obtener exclusivamente audio procesado.
                 * Este método lee de colaAudioProcesado.
                 */
                ServicioDecodificador.ContenedorAudio audioData =
                        servicio.getSiguienteAudioProcesado();

                if (audioData == null
                        || audioData.samples() == null
                        || audioData.samples().length == 0) {

                    continue;
                }

                int canales = audioData.numChannels();
                float sampleRate = audioData.sampleRate();

                // Configurar la línea de audio al recibir el primer bloque.
                if (lineaAudio == null) {

                    AudioFormat formato = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate,
                            16,
                            canales,
                            canales * 2,
                            sampleRate,
                            false
                    );

                    lineaAudio =
                            AudioSystem.getSourceDataLine(formato);

                    lineaAudio.open(formato);
                    lineaAudio.start();

                    /*
                     * Establecer el reloj maestro al comenzar
                     * la salida física del audio.
                     */
                    timestampInicioAudio =
                            audioData.timestampMicros();

                    tiempoInicioReproduccion =
                            System.nanoTime();
                }

                /*
                 * Conversión de muestras a bytes PCM.
                 * No se aplica ningún efecto DSP aquí.
                 */
                byte[] bytesAudio =
                        Util.convertirMuestrasABytesPCM(
                                audioData.samples(),
                                canales
                        );

                // Enviar el bloque de audio al hardware.
                if (bytesAudio != null
                        && bytesAudio.length > 0) {

                    lineaAudio.write(
                            bytesAudio,
                            0,
                            bytesAudio.length
                    );
                }
            }

        } catch (InterruptedException ex) {

            Thread.currentThread().interrupt();

        } catch (Exception ex) {

            System.err.println(
                    "[Controlador] Error en audio: "
                            + ex.getMessage()
            );

        } finally {

            // Liberar correctamente el dispositivo de audio.
            if (lineaAudio != null) {

                lineaAudio.drain();
                lineaAudio.stop();
                lineaAudio.close();
            }

            // Indicar que la reproducción terminó.
            servicio.finalizarReproduccion();
        }
    }

    // ---------------------------------------------------------
    // HILO DSP: PROCESAMIENTO DE AUDIO
    // ---------------------------------------------------------

    /**
     * Consume audio original, aplica eco y carga de CPU,
     * y deposita el bloque procesado en la cola intermedia.
     *
     * Este hilo desacopla el procesamiento matemático
     * de la reproducción física del audio.
     */
    private void cicloProcesamientoAudio() {

        try {

            while (servicio.isEjecutando()
                    && (!servicio.isDecodificacionTerminada()
                    || servicio.hayAudioPendiente())) {

                try {

                    /*
                     * Obtener audio decodificado desde colaAudio.
                     */
                    ServicioDecodificador.ContenedorAudio audioData =
                            servicio.getSiguienteAudio();

                    if (audioData == null
                            || audioData.samples() == null
                            || audioData.samples().length == 0) {

                        continue;
                    }

                    // Medir cuánto tarda el procesamiento DSP.
                    long inicioDSP = System.nanoTime();

                    /*
                     * Aplicar eco y carga artificial de CPU.
                     * Se usa el sample rate real del bloque.
                     */
                    Util.aplicarEfectoEcoSincrono(
                            audioData.samples(),
                            audioData.numChannels(),
                            (int) audioData.sampleRate()
                    );

                    long tiempoDSP =
                            (System.nanoTime() - inicioDSP)
                                    / 1_000_000;

                    System.out.println(
                            "[DSP] Tiempo de procesamiento: "
                                    + tiempoDSP + " ms"
                    );

                    /*
                     * Depositar el bloque procesado en la cola intermedia.
                     * El consumidor de audio lo reproducirá después.
                     */
                    servicio.agregarAudioProcesado(audioData);

                } catch (InterruptedException ex) {

                    Thread.currentThread().interrupt();
                    break;

                } catch (Exception ex) {

                    System.err.println(
                            "[Controlador] Error DSP: "
                                    + ex.getMessage()
                    );
                }
            }

        } finally {

            // Avisar al consumidor que el DSP ya no producirá más audio.
            servicio.marcarProcesamientoAudioTerminado();
        }
    }
}