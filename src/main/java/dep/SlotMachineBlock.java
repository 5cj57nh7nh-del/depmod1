package dep;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class SlotMachineBlock extends Block {
    public static final MapCodec<SlotMachineBlock> CODEC = createCodec(SlotMachineBlock::new);

    public SlotMachineBlock(Settings settings) { super(settings); }

    @Override
    protected MapCodec<? extends Block> getCodec() { return CODEC; }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity sp) {
            DepMod.spinSlot(sp);
        }
        return ActionResult.SUCCESS;
    }
}
