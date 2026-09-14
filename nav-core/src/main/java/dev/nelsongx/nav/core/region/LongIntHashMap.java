package dev.nelsongx.nav.core.region;

/** Minimal open-addressing {@code long -> int} map for non-negative values; not thread-safe. */
final class LongIntHashMap {
  private long[] keys;
  private int[] values; // stored as value + 1; 0 = empty slot
  private int size;

  LongIntHashMap(int expected) {
    int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
    keys = new long[cap];
    values = new int[cap];
  }

  private static int hash(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return (int) k;
  }

  /** Value for {@code key}, or -1 if absent. */
  int get(long key) {
    int mask = keys.length - 1;
    for (int i = hash(key) & mask; values[i] != 0; i = (i + 1) & mask) {
      if (keys[i] == key) {
        return values[i] - 1;
      }
    }
    return -1;
  }

  /** Associates {@code value} (0 .. Integer.MAX_VALUE - 1) with {@code key}. */
  void put(long key, int value) {
    if ((size + 1) * 2 > keys.length) {
      rehash();
    }
    int mask = keys.length - 1;
    int i = hash(key) & mask;
    while (values[i] != 0) {
      if (keys[i] == key) {
        values[i] = value + 1;
        return;
      }
      i = (i + 1) & mask;
    }
    keys[i] = key;
    values[i] = value + 1;
    size++;
  }

  private void rehash() {
    long[] oldKeys = keys;
    int[] oldValues = values;
    keys = new long[oldKeys.length * 2];
    values = new int[oldValues.length * 2];
    size = 0;
    for (int i = 0; i < oldKeys.length; i++) {
      if (oldValues[i] != 0) {
        put(oldKeys[i], oldValues[i] - 1);
      }
    }
  }
}
