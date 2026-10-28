package com.cuscus.wifiaudiostreaming;

/** Exact digital silence; quiet but nonzero audio must still be transmitted. */
public final class PcmSilence {
    private PcmSilence() {}

    public static boolean isZero(byte[] pcm, int offset, int length) {
        if (offset < 0 || length < 0 || offset > pcm.length - length) {
            throw new IndexOutOfBoundsException("Invalid PCM range");
        }
        for (int i = offset; i < offset + length; i++) {
            if (pcm[i] != 0) return false;
        }
        return true;
    }
}
