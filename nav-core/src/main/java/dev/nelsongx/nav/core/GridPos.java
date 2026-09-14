package dev.nelsongx.nav.core;

/**
 * An integer block position in a world.
 *
 * <p>When used as a path point or standing position, {@code y} is the <em>feet</em> block: the block
 * the entity's feet occupy, i.e. one above the block it stands on.
 *
 * @param x block x coordinate
 * @param y block y coordinate (feet block)
 * @param z block z coordinate
 */
public record GridPos(int x, int y, int z) {
}
