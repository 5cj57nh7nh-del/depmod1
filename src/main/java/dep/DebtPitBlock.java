package dep;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Поставил блок — над ним теперь точка долговой ямы. Строй вокруг клетку. */
public class DebtPitBlock extends Block {
    public static final MapCodec<DebtPitBlock> CODEC = createCodec(DebtPitBlock::new);

    public DebtPitBlock(Settings settings) { super(settings); }

    @Override
    protected MapCodec<? extends Block> getCodec() { return CODEC; }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient && world instanceof ServerWorld sw) {
            DepMod.setPit(sw, pos.up());
            if (placer instanceof PlayerEntity pl) {
                pl.sendMessage(Text.literal("Долговая яма теперь над этим блоком. Построй клетку!"), true);
            }
        }
    }
}
