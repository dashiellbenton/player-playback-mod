package com.playerplayback.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Encodes item stacks to NBT so they can be stored inside recordings. */
public final class ItemCodec {
    private ItemCodec() {}

    public static CompoundTag encode(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag wrapper = new CompoundTag();
        if (stack == null || stack.isEmpty()) return wrapper;
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(tag -> wrapper.put("i", tag));
        return wrapper;
    }

    public static ItemStack decode(CompoundTag wrapper, HolderLookup.Provider registries) {
        if (wrapper == null) return ItemStack.EMPTY;
        Tag tag = wrapper.get("i");
        if (tag == null) return ItemStack.EMPTY;
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
    }
}
