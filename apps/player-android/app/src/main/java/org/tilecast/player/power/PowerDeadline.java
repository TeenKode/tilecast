package org.tilecast.player.power;
public final class PowerDeadline {
  public static final long TIMEOUT_MS=9000L;
  private PowerDeadline(){}
  public static boolean active(long deadline,long now) {
    return now>=0 && deadline>now && deadline-now<=TIMEOUT_MS;
  }
}
