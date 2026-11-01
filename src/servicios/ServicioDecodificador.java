package servicios;

import java.lang.management.ManagementFactory;
import java.nio.Buffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

public class ServicioDecodificador {

    public static final int TAMAÑO_MAXIMO_COLA = 30;

    private final OperatingSystemMXBean beanSO =
            (OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();

    // Indica si el reproductor continúa ejecutándose.
    private volatile boolean ejecutando = false;

    // Indica que FFmpeg terminó de decodificar el archivo.
    private volatile boolean decodificacionTerminada = false;

    // Indica que el hilo DSP terminó de procesar el audio.
    private volatile boolean procesamientoAudioTerminado = false;

    public record ContenedorFrame(
            Frame frame,
            long timestampMicros) {
    }

    public record ContenedorAudio(
            Buffer[] samples,
            int numChannels,
            int sampleRate,
            long timestampMicros) {
    }

    public record Metricas(
            long memoriaHeapMB,
            long memoriaOffHeapMB,
            double porcentajeUsoCPU,
            int ocupacionCola,
            long tiempoLectura) {
    }

    private final BlockingQueue<ContenedorFrame> colaVideo =
            new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    private final BlockingQueue<ContenedorAudio> colaAudio =
            new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    private final BlockingQueue<ContenedorAudio> colaAudioProcesado =
            new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    public boolean isEjecutando() {
        return ejecutando;
    }

    public boolean isDecodificacionTerminada() {
        return decodificacionTerminada;
    }

    public boolean isProcesamientoAudioTerminado() {
        return procesamientoAudioTerminado;
    }

    public boolean hayVideoPendiente() {
        return !colaVideo.isEmpty();
    }

    public boolean hayAudioPendiente() {
        return !colaAudio.isEmpty();
    }

    public boolean hayAudioProcesadoPendiente() {
        return !colaAudioProcesado.isEmpty();
    }

    public void marcarProcesamientoAudioTerminado() {
        procesamientoAudioTerminado = true;
    }

    public void finalizarReproduccion() {
        ejecutando = false;
    }

    public void iniciarDecodificacion(String rutaVideo) {

        ejecutando = true;
        decodificacionTerminada = false;
        procesamientoAudioTerminado = false;

        // Limpiar colas de una reproducción anterior.
        colaVideo.clear();
        colaAudio.clear();
        colaAudioProcesado.clear();

        try (var grabber = new FFmpegFrameGrabber(rutaVideo)) {

            grabber.start();

            Frame frameActual;

            while (ejecutando
                    && (frameActual = grabber.grab()) != null) {

                // Timestamp real del contenido multimedia.
                long timestampMicros = grabber.getTimestamp();

                // Procesar video.
                if (frameActual.image != null) {

                    Frame frameClonado = frameActual.clone();

                    colaVideo.put(
                            new ContenedorFrame(
                                    frameClonado,
                                    timestampMicros
                            )
                    );
                }

                // Procesar audio.
                if (frameActual.samples != null) {

                    Buffer[] samplesClonados =
                            new Buffer[frameActual.samples.length];

                    for (int i = 0;
                         i < frameActual.samples.length;
                         i++) {

                        samplesClonados[i] =
                                Util.duplicarBuffer(
                                        frameActual.samples[i]
                                );
                    }

                    colaAudio.put(
                            new ContenedorAudio(
                                    samplesClonados,
                                    frameActual.audioChannels,
                                    frameActual.sampleRate,
                                    timestampMicros
                            )
                    );
                }
            }

        } catch (InterruptedException ex) {

            Thread.currentThread().interrupt();

        } catch (Exception ex) {

            System.err.println(
                    "[Servicio] Error de decodificación: "
                            + ex.getMessage()
            );

        } finally {

            // No detener todo el reproductor aquí.
            // Los consumidores deben vaciar las colas pendientes.
            decodificacionTerminada = true;
        }
    }

    public ContenedorFrame getSiguienteFrame()
            throws InterruptedException {

        return colaVideo.poll(100, TimeUnit.MILLISECONDS);
    }

    public ContenedorAudio getSiguienteAudio()
            throws InterruptedException {

        return colaAudio.poll(100, TimeUnit.MILLISECONDS);
    }

    public ContenedorAudio getSiguienteAudioProcesado()
            throws InterruptedException {

        return colaAudioProcesado.poll(100, TimeUnit.MILLISECONDS);
    }

    public void agregarAudioProcesado(
            ContenedorAudio audio) throws InterruptedException {

        colaAudioProcesado.put(audio);
    }

    public void detener() {

        ejecutando = false;

        decodificacionTerminada = true;
        procesamientoAudioTerminado = true;

        colaVideo.clear();
        colaAudio.clear();
        colaAudioProcesado.clear();
    }

    public Metricas getMetricas(long tiempoLectura) {

        Runtime runtime = Runtime.getRuntime();
        long mb = 1048576;

        long memoriaHeapMB =
                (runtime.totalMemory() - runtime.freeMemory()) / mb;

        long memoriaProceso =
                beanSO.getCommittedVirtualMemorySize() / mb;

        long memoriaOffHeapMB =
                Math.max(0, memoriaProceso - memoriaHeapMB);

        double porcentajeUsoCPU =
                beanSO.getCpuLoad() * 100;

        return new Metricas(
                memoriaHeapMB,
                memoriaOffHeapMB,
                porcentajeUsoCPU,
                colaVideo.size(),
                tiempoLectura
        );
    }
}