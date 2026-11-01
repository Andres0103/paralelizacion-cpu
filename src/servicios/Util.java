package servicios;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.stream.IntStream;

public class Util {

    /*
     * Parámetros del efecto de eco.
     *
     * RETARDO_ECO_MS:
     * Tiempo que debe transcurrir antes de escuchar la repetición.
     *
     * GANANCIA_ECO:
     * Volumen de la señal retardada respecto a la señal original.
     */
    private static final int RETARDO_ECO_MS = 250;
    private static final float GANANCIA_ECO = 0.20f;

    /*
     * Historial del eco.
     *
     * historialEco[canal][muestra]
     *
     * Cada canal tiene su propio historial para evitar mezclar
     * las muestras del canal izquierdo con las del derecho.
     */
    private static float[][] historialEco;

    // Posición actual dentro del historial circular.
    private static int posicionEco = 0;

    // Datos del formato para detectar cambios de audio.
    private static int sampleRateEco = -1;
    private static int canalesEco = -1;

    /**
     * Duplica un buffer de JavaCV.
     *
     * Se utiliza en el productor para que los datos que se entregan
     * a las colas no dependan de la memoria mutable de FFmpeg.
     *
     * Se copia únicamente la región remaining() del buffer original.
     */
    public static Buffer duplicarBuffer(Buffer original) {

        if (original == null) {
            return null;
        }

        if (original instanceof FloatBuffer fb) {

            FloatBuffer copia = FloatBuffer.allocate(fb.remaining());
            copia.put(fb.duplicate());
            copia.flip();

            return copia;

        } else if (original instanceof ShortBuffer sb) {

            ShortBuffer copia = ShortBuffer.allocate(sb.remaining());
            copia.put(sb.duplicate());
            copia.flip();

            return copia;

        } else if (original instanceof ByteBuffer bb) {

            ByteBuffer copia = ByteBuffer.allocate(bb.remaining());
            copia.put(bb.duplicate());
            copia.flip();

            return copia;
        }

        /*
         * Para tipos no soportados se retorna el buffer original.
         * En este proyecto se espera FloatBuffer o ShortBuffer.
         */
        return original;
    }

    /**
     * Convierte las muestras de audio a PCM de 16 bits little-endian.
     *
     * Soporta:
     * - FloatBuffer planar estéreo: un buffer por canal.
     * - FloatBuffer mono o intercalado.
     * - ShortBuffer intercalado.
     *
     * El resultado queda preparado para SourceDataLine.
     */
    public static byte[] convertirMuestrasABytesPCM(
            Buffer[] buffers,
            int canales) {

        if (buffers == null
                || buffers.length == 0
                || buffers[0] == null
                || canales <= 0) {

            return new byte[0];
        }

        Buffer principal = buffers[0];

        // Caso FloatBuffer
        if (principal instanceof FloatBuffer) {

            FloatBuffer left = ((FloatBuffer) principal).duplicate();
            left.rewind();

            /*
             * Estéreo planar:
             * buffers[0] = canal izquierdo
             * buffers[1] = canal derecho
             */
            if (canales == 2
                    && buffers.length >= 2
                    && buffers[1] instanceof FloatBuffer) {

                FloatBuffer right =
                        ((FloatBuffer) buffers[1]).duplicate();

                right.rewind();

                int cantidad = Math.min(
                        left.remaining(),
                        right.remaining()
                );

                // Dos canales, dos bytes por muestra.
                byte[] bytes = new byte[cantidad * 4];
                int indice = 0;

                while (left.hasRemaining()
                        && right.hasRemaining()) {

                    short muestraIzquierda = floatToShort(left.get());
                    short muestraDerecha = floatToShort(right.get());

                    // Canal izquierdo: little-endian.
                    bytes[indice++] = (byte) (muestraIzquierda & 0xff);
                    bytes[indice++] =
                            (byte) ((muestraIzquierda >> 8) & 0xff);

                    // Canal derecho: little-endian.
                    bytes[indice++] = (byte) (muestraDerecha & 0xff);
                    bytes[indice++] =
                            (byte) ((muestraDerecha >> 8) & 0xff);
                }

                return bytes;
            }

            /*
             * Mono o FloatBuffer intercalado.
             * En este caso se convierte cada muestra en el orden
             * en que viene almacenada.
             */
            byte[] bytes = new byte[left.remaining() * 2];
            int indice = 0;

            while (left.hasRemaining()) {

                short muestra = floatToShort(left.get());

                bytes[indice++] = (byte) (muestra & 0xff);
                bytes[indice++] = (byte) ((muestra >> 8) & 0xff);
            }

            return bytes;
        }

        // Caso ShortBuffer
        if (principal instanceof ShortBuffer) {

            ShortBuffer sb = ((ShortBuffer) principal).duplicate();
            sb.rewind();

            byte[] bytes = new byte[sb.remaining() * 2];
            int indice = 0;

            while (sb.hasRemaining()) {

                short muestra = sb.get();

                bytes[indice++] = (byte) (muestra & 0xff);
                bytes[indice++] = (byte) ((muestra >> 8) & 0xff);
            }

            return bytes;
        }

        // Formato no soportado.
        return new byte[0];
    }

    /**
     * Convierte una muestra float entre -1.0 y 1.0
     * a una muestra PCM signed de 16 bits.
     */
    private static short floatToShort(float valor) {

        float limitado = limitarMuestra(valor);

        return (short) (limitado * 32767.0f);
    }

    /**
     * Limita una muestra al rango permitido para audio float.
     */
    private static float limitarMuestra(float valor) {

        return Math.max(-1.0f, Math.min(1.0f, valor));
    }

    /**
     * Aplica el efecto de eco a un bloque de audio.
     *
     * Este método debe ser llamado únicamente desde el hilo DSP,
     * no desde el hilo que escribe en SourceDataLine.
     *
     * @param buffers buffers de muestras decodificados
     * @param canales cantidad de canales de audio
     * @param sampleRate frecuencia de muestreo real
     */
    public static synchronized void aplicarEfectoEcoSincrono(
            Buffer[] buffers,
            int canales,
            int sampleRate) {

        if (buffers == null
                || buffers.length == 0
                || buffers[0] == null
                || canales <= 0
                || sampleRate <= 0) {

            return;
        }

        // Crear o reiniciar el historial si cambia el formato.
        inicializarHistorialEco(canales, sampleRate);

        Buffer principal = buffers[0];

        /*
         * FloatBuffer planar:
         * hay un buffer independiente por canal.
         *
         * Se procesa el bloque completo por índice de muestra
         * para que ambos canales avancen sincronizados.
         */
        if (principal instanceof FloatBuffer
                && buffers.length >= canales
                && canales > 1) {

            aplicarEcoFloatPlanar(buffers, canales);

        } else if (principal instanceof FloatBuffer fb) {

            /*
             * FloatBuffer único:
             * se interpreta como mono o muestras intercaladas.
             */
            aplicarEcoFloatIntercalado(fb, canales);

        } else if (principal instanceof ShortBuffer sb) {

            // ShortBuffer se procesa como muestras intercaladas.
            aplicarEcoShortIntercalado(sb, canales);
        }

        /*
         * Carga artificial de CPU paralelizada.
         *
         * Esta operación se ejecuta en el hilo DSP.
         * No debe colocarse en cicloAudio().
         */
        double resultado = IntStream.range(0, 500000)
                .parallel()
                .mapToDouble(j -> Math.sin(j))
                .sum();

        // Evita que el resultado quede completamente sin utilizar.
        if (Double.isInfinite(resultado)) {
            System.out.println(resultado);
        }
    }

    /**
     * Inicializa el historial circular del eco.
     */
    private static void inicializarHistorialEco(
            int canales,
            int sampleRate) {

        if (historialEco == null
                || sampleRateEco != sampleRate
                || canalesEco != canales) {

            sampleRateEco = sampleRate;
            canalesEco = canales;

            // Convertir el retardo en cantidad de muestras.
            int retardoMuestras =
                    sampleRate * RETARDO_ECO_MS / 1000;

            historialEco = new float[canales][retardoMuestras];

            posicionEco = 0;
        }
    }

    /**
     * Aplica eco a audio FloatBuffer planar.
     *
     * Para cada instante:
     * 1. Lee la muestra original de cada canal.
     * 2. Obtiene la muestra retardada de cada historial.
     * 3. Suma la señal original y el eco.
     * 4. Guarda la muestra original en el historial.
     * 5. Avanza una posición del historial.
     */
    private static void aplicarEcoFloatPlanar(
            Buffer[] buffers,
            int canales) {

        FloatBuffer[] canalesBuffer = new FloatBuffer[canales];
        float[][] muestras = new float[canales][];

        int cantidadMuestras = Integer.MAX_VALUE;

        // Obtener los buffers de cada canal.
        for (int canal = 0; canal < canales; canal++) {

            if (!(buffers[canal] instanceof FloatBuffer fb)) {
                return;
            }

            canalesBuffer[canal] = fb;
            fb.rewind();

            muestras[canal] = new float[fb.remaining()];

            cantidadMuestras = Math.min(
                    cantidadMuestras,
                    muestras[canal].length
            );

            fb.get(muestras[canal]);
        }

        // Procesar todos los canales sincronizados.
        for (int i = 0; i < cantidadMuestras; i++) {

            for (int canal = 0; canal < canales; canal++) {

                float original = muestras[canal][i];

                float retardada =
                        historialEco[canal][posicionEco];

                float salida =
                        original + retardada * GANANCIA_ECO;

                muestras[canal][i] = limitarMuestra(salida);

                // Guardar la señal original, no la salida mezclada.
                historialEco[canal][posicionEco] = original;
            }

            // Avanzar una sola vez por instante de audio.
            avanzarPosicionEco();
        }

        // Escribir las muestras procesadas en sus buffers.
        for (int canal = 0; canal < canales; canal++) {

            FloatBuffer fb = canalesBuffer[canal];

            fb.rewind();
            fb.put(muestras[canal]);
            fb.rewind();
        }
    }

    /**
     * Aplica eco a un FloatBuffer con muestras intercaladas.
     * Ejemplo estéreo: L, R, L, R...
     */
    private static void aplicarEcoFloatIntercalado(
            FloatBuffer buffer,
            int canales) {

        buffer.rewind();

        int cantidad = buffer.remaining();
        float[] muestras = new float[cantidad];

        buffer.get(muestras);

        for (int i = 0; i < muestras.length; i++) {

            int canal = i % canales;

            float original = muestras[i];

            float retardada =
                    historialEco[canal][posicionEco];

            float salida =
                    original + retardada * GANANCIA_ECO;

            muestras[i] = limitarMuestra(salida);

            historialEco[canal][posicionEco] = original;

            // Avanzar cuando se haya procesado el último canal.
            if (canal == canales - 1) {
                avanzarPosicionEco();
            }
        }

        buffer.rewind();
        buffer.put(muestras);
        buffer.rewind();
    }

    /**
     * Aplica eco a un ShortBuffer intercalado.
     *
     * Las muestras se convierten temporalmente a float
     * para realizar la suma del efecto.
     */
    private static void aplicarEcoShortIntercalado(
            ShortBuffer buffer,
            int canales) {

        buffer.rewind();

        int cantidad = buffer.remaining();
        short[] muestras = new short[cantidad];

        buffer.get(muestras);

        for (int i = 0; i < muestras.length; i++) {

            int canal = i % canales;

            float original = muestras[i] / 32768.0f;

            float retardada =
                    historialEco[canal][posicionEco];

            float salida =
                    original + retardada * GANANCIA_ECO;

            salida = limitarMuestra(salida);

            muestras[i] = floatToShort(salida);

            // Guardar la muestra original para el próximo retardo.
            historialEco[canal][posicionEco] = original;

            if (canal == canales - 1) {
                avanzarPosicionEco();
            }
        }

        buffer.rewind();
        buffer.put(muestras);
        buffer.rewind();
    }

    /**
     * Avanza el índice del historial circular.
     */
    private static void avanzarPosicionEco() {

        posicionEco++;

        if (posicionEco >= historialEco[0].length) {
            posicionEco = 0;
        }
    }
}