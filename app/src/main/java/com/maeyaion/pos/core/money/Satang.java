package com.maeyaion.pos.core.money;
import java.util.Objects;
public final class Satang {
    public static final Satang ZERO = new Satang(0L);
    private final long value;
    private Satang(long value) { this.value = value; }
    public static Satang of(long value) { return value == 0L ? ZERO : new Satang(value); }
    public long value() { return value; }
    public Satang plus(Satang other) {
        Objects.requireNonNull(other, "other");
        return of(Math.addExact(value, other.value));
    }
    public Satang minus(Satang other) {
        Objects.requireNonNull(other, "other");
        return of(Math.subtractExact(value, other.value));
    }
    @Override public boolean equals(Object other) {
        return this == other || (other instanceof Satang && value == ((Satang) other).value);
    }
    @Override public int hashCode() { return Long.hashCode(value); }
    @Override public String toString() { return "Satang(" + value + ")"; }
}
