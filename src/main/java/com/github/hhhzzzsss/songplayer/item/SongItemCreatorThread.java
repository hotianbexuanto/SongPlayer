package com.github.hhhzzzsss.songplayer.item;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.conversion.SPConverter;
import com.github.hhhzzzsss.songplayer.song.SongLoaderThread;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.io.IOException;
import java.util.List;

public class SongItemCreatorThread extends SongLoaderThread {
    public final int slotId;
    public final ItemStack stack;
    public SongItemCreatorThread(String location) throws IOException {
        super(location);
        this.slotId = SongPlayer.MC.player.getInventory().getSelectedSlot();
        this.stack = SongPlayer.MC.player.getInventory().getStack(slotId);
    }

    @Override
    public void run() {
        super.run();
        byte[] songData;
        try {
            songData = SPConverter.getBytesFromSong(song);
        } catch (IOException e) {
            Util.showChatMessage("§c创建歌曲物品时出错: §4" + e.getMessage());
            return;
        }
        SongPlayer.MC.execute(() -> {
            if (SongPlayer.MC.world == null) {
                return;
            }
            if (!SongPlayer.MC.player.getInventory().getStack(slotId).equals(stack)) {
                Util.showChatMessage("§c无法创建歌曲物品，因为物品已移动");
            }
            ItemStack newStack;
            if (stack.isEmpty()) {
                newStack = Items.PAPER.getDefaultStack();
                // When going from 1.21.3 -> 1.21.4, datafixer changes the custom model data to a float array with one element
                newStack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of(751642938f), List.of(), List.of(), List.of()));
            }
            else {
                newStack = stack.copy();
            }
            newStack = SongItemUtils.createSongItem(newStack, songData, filename, song.name);
            SongPlayer.MC.player.getInventory().setStack(slotId, newStack);
            SongPlayer.MC.interactionManager.clickCreativeStack(SongPlayer.MC.player.getStackInHand(Hand.MAIN_HAND), 36 + slotId);
            Util.showChatMessage(Text.literal("§6已成功将歌曲数据分配给 §3").append(newStack.getItem().getName()));
        });
    }
}
