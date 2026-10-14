package com.cuscus.wifiaudiostreaming;

/** PCM16 little-endian gain with a continuous soft knee above unity. */
public final class PcmGainLimiter {
    private PcmGainLimiter() { }

    /**
     * Returns the number of samples compressed by the knee. At unity the input
     * bytes are left untouched. Amplified peaks are intentionally nonlinear;
     * this prevents integer clipping, not a claim of lossless amplification.
     *
     * The curve depends only on the sample and requested gain, so packet sizes
     * cannot change its response. Its value and slope are continuous at the
     * knee. The knee width tends to zero as gain tends to unity, avoiding a
     * sudden reduction when the user first turns amplification on.
     */
    public static long applyInPlace(byte[] pcm, int offset, int length, float gain) {
        validate(pcm, offset, length, gain);
        if (gain == 1.0f) return 0;
        long limitedSamples = 0;
        for (int at = offset; at < offset + length; at += 2) {
            int sample = (short) ((pcm[at] & 255) | (pcm[at + 1] << 8));
            double amplified = sample * (double) gain;
            double magnitude = Math.abs(amplified);
            if (gain > 1.0f) {
                double limit = sample < 0 ? 32768.0 : 32767.0;
                double width = limit * 0.25 * (gain - 1.0);
                double threshold = limit - width;
                if (magnitude > threshold) {
                    double excess = magnitude - threshold;
                    magnitude = threshold + width * excess / (width + excess);
                    amplified = Math.copySign(magnitude, amplified);
                    limitedSamples++;
                }
            }
            int scaled = (int) Math.round(amplified);
            pcm[at] = (byte) scaled;
            pcm[at + 1] = (byte) (scaled >> 8);
        }
        return limitedSamples;
    }

    /** Diagnostic count before applyInPlace: samples that hard gain would clip. */
    public static long countWouldClip(byte[] pcm, int offset, int length, float gain) {
        validate(pcm, offset, length, gain);
        long count = 0;
        for (int at = offset; at < offset + length; at += 2) {
            int sample = (short) ((pcm[at] & 255) | (pcm[at + 1] << 8));
            double amplified = sample * (double) gain;
            if (amplified > 32767.0 || amplified < -32768.0) count++;
        }
        return count;
    }

    private static void validate(byte[] pcm, int offset, int length, float gain) {
        if (pcm == null || offset < 0 || length < 0 || offset > pcm.length - length
                || (offset & 1) != 0 || (length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 range must contain complete samples");
        }
        if (Float.isNaN(gain) || Float.isInfinite(gain) || gain < 0.0f || gain > 2.0f) {
            throw new IllegalArgumentException("PCM gain must be finite and between 0 and 2");
        }
    }
}
