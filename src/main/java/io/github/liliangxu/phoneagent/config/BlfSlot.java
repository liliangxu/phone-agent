package io.github.liliangxu.phoneagent.config;

/**
 * Immutable startup mapping between a 1-based application slot and the BLF
 * extension configured for that slot on the phone/Asterisk side.
 */
public record BlfSlot(int slot, String extension) {
}
