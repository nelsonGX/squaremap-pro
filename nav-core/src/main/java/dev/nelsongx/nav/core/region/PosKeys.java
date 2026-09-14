package dev.nelsongx.nav.core.region;

/**
 * Packs block positions into {@code long} keys. Same layout as {@code AStar}'s private helper:
 * x (26 bits) | z (26 bits) | y (12 bits), two's complement, sign-extended on unpack.
 */
final class PosKeys {

  static final int XZ_BITS = 26;
  static final int Y_BITS = 12;
  static final int XZ_MIN = -(1 << (XZ_BITS - 1));
  static final int XZ_MAX = (1 << (XZ_BITS - 1)) - 1;
  static final int Y_MIN = -(1 << (Y_BITS - 1));
  static final int Y_MAX = (1 << (Y_BITS - 1)) - 1;
  private static final long XZ_MASK = (1L << XZ_BITS) - 1;
  private static final long Y_MASK = (1L << Y_BITS) - 1;

  private PosKeys() {
  }

  static long pack(int x, int y, int z) {
    return ((x & XZ_MASK) << (Y_BITS + XZ_BITS)) | ((z & XZ_MASK) << Y_BITS) | (y & Y_MASK);
  }

  static int x(long key) {
    return (int) (key >> (Y_BITS + XZ_BITS));
  }

  static int z(long key) {
    return (int) ((key << XZ_BITS) >> (XZ_BITS + Y_BITS));
  }

  static int y(long key) {
    return (int) ((key << (64 - Y_BITS)) >> (64 - Y_BITS));
  }

  /** Compares two packed keys by (y, z, x) ascending, i.e. {@link Region#NODE_ORDER}. */
  static int compareYzx(long a, long b) {
    int c = Integer.compare(y(a), y(b));
    if (c != 0) {
      return c;
    }
    c = Integer.compare(z(a), z(b));
    return c != 0 ? c : Integer.compare(x(a), x(b));
  }
}
