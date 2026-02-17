package cat.maki.makiscreen.util;

import cat.maki.makiscreen.screen.MapTile;

public class ByteArrayPool {

    private static final ThreadLocal<PooledBuffer> TILE_BUFFERS = ThreadLocal.withInitial(() ->
        new PooledBuffer(MapTile.SIZE * MapTile.SIZE)
    );

    private static final ThreadLocal<PooledBuffer> LARGE_BUFFERS = ThreadLocal.withInitial(() ->
        new PooledBuffer(MapTile.SIZE * MapTile.SIZE * 2)
    );

    private static final ThreadLocal<ResizableByteBuffer> FRAME_BYTE_BUFFER_1 = ThreadLocal.withInitial(ResizableByteBuffer::new);
    private static final ThreadLocal<ResizableByteBuffer> FRAME_BYTE_BUFFER_2 = ThreadLocal.withInitial(ResizableByteBuffer::new);
    private static final ThreadLocal<ResizableByteBuffer> FRAME_BYTE_BUFFER_3 = ThreadLocal.withInitial(ResizableByteBuffer::new);
    private static final ThreadLocal<ResizableIntBuffer> FRAME_INT_BUFFER_1 = ThreadLocal.withInitial(ResizableIntBuffer::new);
    private static final ThreadLocal<ResizableIntBuffer> FRAME_INT_BUFFER_2 = ThreadLocal.withInitial(ResizableIntBuffer::new);

    private static class PooledBuffer {
        private final byte[] buffer;
        private boolean inUse = false;

        PooledBuffer(int size) {
            this.buffer = new byte[size];
        }

        byte[] acquire(int minSize) {
            if (inUse || minSize > buffer.length) {
                // Pool exhausted or size too large, allocate new
                return new byte[minSize];
            }
            inUse = true;
            return buffer;
        }

        void release() {
            inUse = false;
        }
    }

    public static byte[] getTileBuffer(int size) {
        return TILE_BUFFERS.get().acquire(size);
    }

    public static void releaseTileBuffer() {
        TILE_BUFFERS.get().release();
    }

    public static byte[] getLargeBuffer(int size) {
        return LARGE_BUFFERS.get().acquire(size);
    }

    public static void releaseLargeBuffer() {
        LARGE_BUFFERS.get().release();
    }

    public static class ResizableByteBuffer {
        private byte[] buffer = new byte[0];

        public byte[] ensureCapacity(int size) {
            if (buffer.length < size) {
                // Allocate with some headroom to reduce future reallocations
                buffer = new byte[size + (size >> 3)]; // +12.5% headroom
            }
            return buffer;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public int capacity() {
            return buffer.length;
        }
    }

    public static class ResizableIntBuffer {
        private int[] buffer = new int[0];

        public int[] ensureCapacity(int size) {
            if (buffer.length < size) {
                buffer = new int[size + (size >> 3)]; // +12.5% headroom
            }
            return buffer;
        }

        public int[] getBuffer() {
            return buffer;
        }

        public int capacity() {
            return buffer.length;
        }
    }

    public static ResizableByteBuffer getFrameByteBuffer1() {
        return FRAME_BYTE_BUFFER_1.get();
    }

    public static ResizableByteBuffer getFrameByteBuffer2() {
        return FRAME_BYTE_BUFFER_2.get();
    }

    public static ResizableByteBuffer getFrameByteBuffer3() {
        return FRAME_BYTE_BUFFER_3.get();
    }

    public static ResizableIntBuffer getFrameIntBuffer1() {
        return FRAME_INT_BUFFER_1.get();
    }

    public static ResizableIntBuffer getFrameIntBuffer2() {
        return FRAME_INT_BUFFER_2.get();
    }
}
